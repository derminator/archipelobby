package com.github.derminator.archipelobby.discord

import com.github.derminator.archipelobby.security.BotLoginService
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.discordjson.json.ApplicationCommandRequest
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import reactor.core.Disposable

@Component
@Profile("discord")
class DiscordLoginBot(
    gateway: GatewayDiscordClient,
    private val botLoginService: BotLoginService
) {
    private val logger = LoggerFactory.getLogger(DiscordLoginBot::class.java)
    private val subscription: Disposable

    init {
        val command = ApplicationCommandRequest.builder()
            .name(COMMAND_NAME)
            .description("Get a one-time Archipelobby login link")
            .build()
        gateway.restClient.applicationId
            .flatMap { applicationId ->
                gateway.restClient.applicationService.createGlobalApplicationCommand(applicationId, command)
            }
            .doOnError { logger.error("Failed to register Discord /login command", it) }
            .onErrorComplete()
            .subscribe()

        subscription = gateway.on(ChatInputInteractionEvent::class.java)
            .filter { it.commandName == COMMAND_NAME }
            .flatMap { event ->
                event.deferReply().withEphemeral(true).then(
                    mono { botLoginService.createLoginLink(event.interaction.user.id.asLong()).orEmpty() }
                        .flatMap { link ->
                            event.editReply(
                                if (link.isNotEmpty()) "Use this one-time link to log in: $link"
                                else "You must be a member of a server shared with this bot to log in."
                            )
                        }
                ).onErrorResume { error ->
                    logger.warn("Failed to handle Discord /login command", error)
                    event.editReply("Unable to create a login link right now. Please try again.")
                        .onErrorComplete()
                }
            }
            .doOnError { logger.error("Discord /login command listener failed", it) }
            .subscribe()
    }

    @PreDestroy
    fun stop() = subscription.dispose()

    companion object {
        private const val COMMAND_NAME = "login"
    }
}
