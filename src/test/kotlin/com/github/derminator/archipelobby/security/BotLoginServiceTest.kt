package com.github.derminator.archipelobby.security

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.UserInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BotLoginServiceTest {
    private val discordService = mock(DiscordService::class.java)
    private val properties = BotLoginProperties().apply {
        baseUrl = "https://lobby.example.com/"
        tokenValidity = Duration.ofMinutes(5)
    }
    private val now = Instant.parse("2026-07-31T12:00:00Z")

    @Test
    fun `creates a one-time login for a server member`() = runBlocking {
        `when`(discordService.isMemberOfAnyGuild(42L)).thenReturn(true)
        `when`(discordService.getUserInfo(42L)).thenReturn(UserInfo(42L, "player"))
        val service = service(token = "generated-token")

        val link = service.createLoginLink(42L)

        assertEquals("https://lobby.example.com/login/bot?token=generated-token", link)
        assertEquals(DiscordPrincipal(42L, "player"), service.consume("generated-token"))
        assertNull(service.consume("generated-token"))
    }

    @Test
    fun `does not create a login for a non-member`() = runBlocking {
        `when`(discordService.isMemberOfAnyGuild(42L)).thenReturn(false)
        val service = service()

        assertNull(service.createLoginLink(42L))
        verify(discordService).isMemberOfAnyGuild(42L)
        verifyNoInteractionsAfterMembershipCheck()
    }

    @Test
    fun `expired login cannot be consumed`() = runBlocking {
        properties.tokenValidity = Duration.ZERO
        `when`(discordService.isMemberOfAnyGuild(42L)).thenReturn(true)
        `when`(discordService.getUserInfo(42L)).thenReturn(UserInfo(42L, "player"))
        val service = service()

        service.createLoginLink(42L)

        assertNull(service.consume("token"))
    }

    private fun service(token: String = "token") = BotLoginService(
        discordService,
        properties,
        Clock.fixed(now, ZoneOffset.UTC)
    ) { token }

    private suspend fun verifyNoInteractionsAfterMembershipCheck() {
        verify(discordService, org.mockito.Mockito.never()).getUserInfo(42L)
    }
}
