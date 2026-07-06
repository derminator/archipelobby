package com.github.derminator.archipelobby.multiserver

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
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
        // Branch inside the coroutine and flatten the resulting Mono<Void>. A prior
        // version used switchIfEmpty to handle the "no running server" case, but
        // switchIfEmpty on a Mono<Void> also fires on the *normal* empty completion
        // of a successful proxy() session, spuriously closing it as SERVICE_RESTARTED.
        return mono {
            val port = activePort(roomId)
            if (port == null) {
                session.close(CloseStatus.SERVICE_RESTARTED)
            } else {
                proxy(session, port)
            }
        }.flatMap { it }
    }

    private suspend fun activePort(roomId: Long): Int? {
        if (!multiServerManager.isRunning(roomId)) return null
        return multiServerManager.getServerPort(roomId)
    }

    private fun proxy(downstream: WebSocketSession, port: Int): Mono<Void> {
        val upstreamUri = URI("ws://${properties.host}:$port")
        return client.execute(upstreamUri) { upstream ->
            val downToUp = upstream.send(downstream.receive().map { forward(it, upstream) })
            val upToDown = downstream.send(upstream.receive().map { forward(it, downstream) })
            Mono.firstWithSignal(downToUp, upToDown)
        }.doOnError { e -> logger.warn("WebSocket proxy error", e) }
    }

    private fun forward(message: WebSocketMessage, target: WebSocketSession): WebSocketMessage {
        val payload = message.payload
        val bytes = ByteArray(payload.readableByteCount())
        payload.read(bytes)
        DataBufferUtils.release(payload)
        return WebSocketMessage(message.type, target.bufferFactory().wrap(bytes))
    }

    private fun extractRoomId(session: WebSocketSession): Long? {
        val match = ROOM_PATH_REGEX.matchEntire(session.handshakeInfo.uri.path) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    companion object {
        private val ROOM_PATH_REGEX = Regex("""/rooms/(\d+)/ws""")
    }
}
