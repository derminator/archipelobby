package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomService
import io.netty.buffer.PooledByteBufAllocator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiServerProxyHandlerTest {

    @Test
    fun `UUID websocket URL resolves to the internal room ID`(): Unit = runBlocking {
        val publicId = "550e8400-e29b-41d4-a716-446655440000"
        val roomService = mock(RoomService::class.java)
        val manager = mock(MultiServerManager::class.java)
        val session = mock(WebSocketSession::class.java)
        val handshake = mock(HandshakeInfo::class.java)
        `when`(session.handshakeInfo).thenReturn(handshake)
        `when`(handshake.uri).thenReturn(URI("ws://localhost/rooms/$publicId/ws"))
        `when`(roomService.resolveRoomUrlId(publicId)).thenReturn(42L)
        `when`(manager.getServerPort(42L)).thenReturn(null)
        `when`(session.close(CloseStatus.SERVICE_RESTARTED)).thenReturn(Mono.empty())

        MultiServerProxyHandler(manager, MultiServerProperties(), roomService)
            .handle(session)
            .block()

        verify(manager).getServerPort(42L)
    }

    @Test
    fun `unknown websocket room closes as not acceptable`(): Unit = runBlocking {
        val publicId = "550e8400-e29b-41d4-a716-446655440000"
        val roomService = mock(RoomService::class.java)
        val manager = mock(MultiServerManager::class.java)
        val session = mock(WebSocketSession::class.java)
        val handshake = mock(HandshakeInfo::class.java)
        `when`(session.handshakeInfo).thenReturn(handshake)
        `when`(handshake.uri).thenReturn(URI("ws://localhost/rooms/$publicId/ws"))
        `when`(roomService.resolveRoomUrlId(publicId)).thenThrow(
            ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"),
        )
        `when`(session.close(CloseStatus.NOT_ACCEPTABLE)).thenReturn(Mono.empty())

        MultiServerProxyHandler(manager, MultiServerProperties(), roomService)
            .handle(session)
            .block()

        verify(session).close(CloseStatus.NOT_ACCEPTABLE)
    }

    @Test
    fun `copying a frame leaves source buffer ownership with receive pipeline`() {
        val sourceFactory = NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT)
        val source = sourceFactory.wrap("hello".toByteArray())
        val message = WebSocketMessage(WebSocketMessage.Type.TEXT, source)

        val copied = copyMessage(message, DefaultDataBufferFactory.sharedInstance)

        assertEquals(WebSocketMessage.Type.TEXT, copied.type)
        val copiedBytes = ByteArray(copied.payload.readableByteCount())
        copied.payload.read(copiedBytes)
        assertContentEquals("hello".toByteArray(), copiedBytes)
        assertTrue(DataBufferUtils.release(source))
    }
}
