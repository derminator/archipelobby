package com.github.derminator.archipelobby.discord

import discord4j.core.GatewayDiscordClient
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordGatewayProviderTest {

    @Test
    fun `returns cached client while connected`() {
        val connectedClient = mock(GatewayDiscordClient::class.java)
        whenever(connectedClient.isConnected).thenReturn(true)

        var loginCount = 0
        val provider = DiscordGatewayProvider("token") {
            loginCount++
            connectedClient
        }

        assertSame(connectedClient, provider.getConnectedClient())
        assertSame(connectedClient, provider.getConnectedClient())
        assertEquals(1, loginCount)
    }

    @Test
    fun `does not cache or return disconnected client from login`() {
        val disconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(disconnectedClient.isConnected).thenReturn(false)
        whenever(disconnectedClient.logout()).thenReturn(Mono.empty())

        val connectedClient = mock(GatewayDiscordClient::class.java)
        whenever(connectedClient.isConnected).thenReturn(true)

        val logins = ArrayDeque(listOf(disconnectedClient, connectedClient))
        val provider = DiscordGatewayProvider("token") { logins.removeFirst() }

        assertThrows<IllegalStateException> { provider.getConnectedClient() }
        assertSame(connectedClient, provider.getConnectedClient())
        assertEquals(0, logins.size)
    }

    @Test
    fun `reconnects when cached client is disconnected even if logout fails`() {
        val disconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(disconnectedClient.isConnected).thenReturn(true, false)
        whenever(disconnectedClient.logout()).thenReturn(Mono.error(RuntimeException("gateway already broken")))

        val reconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(reconnectedClient.isConnected).thenReturn(true)

        val logins = ArrayDeque(listOf(disconnectedClient, reconnectedClient))
        val provider = DiscordGatewayProvider("token") { logins.removeFirst() }

        assertSame(disconnectedClient, provider.getConnectedClient())
        assertSame(reconnectedClient, provider.getConnectedClient())
        assertEquals(0, logins.size)
    }

    @Test
    fun `reconnect waits only until logout timeout for non completing logout`() {
        val disconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(disconnectedClient.isConnected).thenReturn(true, false)
        whenever(disconnectedClient.logout()).thenReturn(Mono.never())

        val reconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(reconnectedClient.isConnected).thenReturn(true)

        val logins = ArrayDeque(listOf(disconnectedClient, reconnectedClient))
        val logoutTimeout = Duration.ofMillis(50)
        val provider = DiscordGatewayProvider("token", logoutTimeout) { logins.removeFirst() }

        assertSame(disconnectedClient, provider.getConnectedClient())

        val startedAt = System.nanoTime()
        assertSame(reconnectedClient, provider.getConnectedClient())
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed >= logoutTimeout, "Reconnect should give stale logout a bounded chance to complete")
        assertTrue(elapsed < Duration.ofSeconds(1), "Reconnect should not wait indefinitely for a non-completing logout")
        assertEquals(0, logins.size)
    }
}
