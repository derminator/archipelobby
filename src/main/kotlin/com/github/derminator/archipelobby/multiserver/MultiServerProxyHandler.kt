package com.github.derminator.archipelobby.multiserver

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.net.URI

/**
 * Reverse-proxy a downstream client WebSocket connection to the local
 * MultiServer process for a given room. Spring serves all rooms on its
 * common port; this handler establishes the upstream connection and pipes
 * frames in both directions.
 */
@Component
class MultiServerProxyHandler(
    private val multiServerManager: MultiServerManager,
    private val properties: MultiServerProperties,
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(MultiServerProxyHandler::class.java)
    private val connectionProvider = ConnectionProvider.create("multiserver-proxy")
    private val client = ReactorNettyWebSocketClient(HttpClient.create(connectionProvider))

    @PreDestroy
    fun shutdown() {
        connectionProvider.dispose()
    }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val roomId = extractRoomId(session) ?: return session.close(CloseStatus.NOT_ACCEPTABLE)
        // getServerPort returns null once the room's process has exited, so a
        // missing port means "no live server" — ask the client to reconnect.
        // Only this branch closes with SERVICE_RESTARTED; a normal proxy()
        // completion just propagates its Mono<Void>.
        val port = activePort(roomId) ?: return session.close(CloseStatus.SERVICE_RESTARTED)
        return proxy(session, port)
    }

    private fun activePort(roomId: Long): Int? = multiServerManager.getServerPort(roomId)

    private fun proxy(downstream: WebSocketSession, port: Int): Mono<Void> {
        val upstreamUri = URI("ws://${properties.host}:$port")
        return client.execute(upstreamUri) { upstream ->
            val downToUp = upstream.send(downstream.receive().map { copyMessage(it, upstream.bufferFactory()) })
            val upToDown = downstream.send(upstream.receive().map { copyMessage(it, downstream.bufferFactory()) })
            Mono.firstWithSignal(downToUp, upToDown)
        }.doOnError { e -> logger.warn("WebSocket proxy error", e) }
    }

    private fun extractRoomId(session: WebSocketSession): Long? {
        val match = ROOM_PATH_REGEX.matchEntire(session.handshakeInfo.uri.path) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    companion object {
        private val ROOM_PATH_REGEX = Regex("""/rooms/(\d+)/ws""")
    }
}

/**
 * Copy a frame between sessions without transferring ownership of the source
 * payload. The receiver pipeline remains responsible for releasing that buffer.
 */
internal fun copyMessage(message: WebSocketMessage, target: DataBufferFactory): WebSocketMessage {
    val payload = message.payload
    val bytes = ByteArray(payload.readableByteCount())
    payload.read(bytes)
    return WebSocketMessage(message.type, target.wrap(bytes))
}
