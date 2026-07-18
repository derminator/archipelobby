package com.github.derminator.archipelobby.multiserver

import io.netty.buffer.PooledByteBufAllocator
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.web.reactive.socket.WebSocketMessage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiServerProxyHandlerTest {

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
