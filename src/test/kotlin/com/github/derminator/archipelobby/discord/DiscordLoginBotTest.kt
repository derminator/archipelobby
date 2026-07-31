package com.github.derminator.archipelobby.discord

import com.github.derminator.archipelobby.security.BotLoginService
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.User
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.common.util.Snowflake
import discord4j.discordjson.json.ApplicationCommandRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Optional

class DiscordLoginBotTest {

    @Test
    fun `responds to login direct message with private login link`() {
        val gateway = mock(GatewayDiscordClient::class.java, RETURNS_DEEP_STUBS)
        val event = mock(MessageCreateEvent::class.java)
        val message = mock(Message::class.java)
        val user = mock(User::class.java)
        val channel = mock(MessageChannel::class.java, RETURNS_DEEP_STUBS)
        val botLoginService = mock(BotLoginService::class.java)
        val link = "https://lobby.example.com/login/bot?token=generated-token"

        whenever(gateway.restClient.applicationId).thenReturn(Mono.just(1L))
        whenever(
            gateway.restClient.applicationService.createGlobalApplicationCommand(
                eq(1L),
                any(ApplicationCommandRequest::class.java)
            )
        ).thenReturn(Mono.empty())
        whenever(gateway.on(ChatInputInteractionEvent::class.java)).thenReturn(Flux.never())
        whenever(gateway.on(MessageCreateEvent::class.java)).thenReturn(Flux.just(event))
        whenever(event.guildId).thenReturn(Optional.empty())
        whenever(event.message).thenReturn(message)
        whenever(message.content).thenReturn(" /LOGIN ")
        whenever(message.author).thenReturn(Optional.of(user))
        whenever(message.channel).thenReturn(Mono.just(channel))
        whenever(user.isBot).thenReturn(false)
        whenever(user.id).thenReturn(Snowflake.of(42L))
        runBlocking { whenever(botLoginService.createLoginLink(42L)).thenReturn(link) }
        val bot = DiscordLoginBot(gateway, botLoginService)

        verify(channel, timeout(1_000)).createMessage("Use this one-time link to log in: $link")
        bot.stop()
    }
}
