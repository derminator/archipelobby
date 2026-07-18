package com.github.derminator.archipelobby.discord

import discord4j.core.GatewayDiscordClient
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

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
    fun `reconnects when cached client is disconnected even if logout fails`() {
        val disconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(disconnectedClient.isConnected).thenReturn(false)
        whenever(disconnectedClient.logout()).thenReturn(Mono.error(RuntimeException("gateway already broken")))

        val reconnectedClient = mock(GatewayDiscordClient::class.java)
        whenever(reconnectedClient.isConnected).thenReturn(true)

        val logins = ArrayDeque(listOf(disconnectedClient, reconnectedClient))
        val provider = DiscordGatewayProvider("token") { logins.removeFirst() }

        assertSame(disconnectedClient, provider.getConnectedClient())
        assertSame(reconnectedClient, provider.getConnectedClient())
        assertEquals(0, logins.size)
    }
}
