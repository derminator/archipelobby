package com.github.derminator.archipelobby.discord

import com.github.derminator.archipelobby.security.BotLoginService
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.discordjson.json.ApplicationCommandRequest
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import reactor.core.Disposable
import reactor.core.Disposables
import reactor.core.publisher.Mono

@Component
@Profile("discord")
class DiscordLoginBot(
    gateway: GatewayDiscordClient,
    private val botLoginService: BotLoginService
) {
    private val logger = LoggerFactory.getLogger(DiscordLoginBot::class.java)
    private val subscriptions: Disposable.Composite = Disposables.composite()

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

        subscriptions.add(gateway.on(ChatInputInteractionEvent::class.java)
            .filter { it.commandName == COMMAND_NAME }
            .flatMap { event ->
                event.deferReply().withEphemeral(true).then(
                    loginResponse(event.interaction.user.id.asLong()).flatMap(event::editReply)
                ).onErrorResume { error ->
                    logger.warn("Failed to handle Discord /login command", error)
                    event.editReply("Unable to create a login link right now. Please try again.")
                        .onErrorComplete()
                }
            }
            .doOnError { logger.error("Discord /login command listener failed", it) }
            .subscribe())

        subscriptions.add(gateway.on(MessageCreateEvent::class.java)
            .filter { event ->
                event.guildId.isEmpty &&
                    event.message.content.trim().equals("/$COMMAND_NAME", ignoreCase = true) &&
                    event.message.author.map { !it.isBot }.orElse(false)
            }
            .flatMap { event ->
                val userId = event.message.author.orElseThrow().id.asLong()
                loginResponse(userId)
                    .flatMap { response -> event.message.channel.flatMap { it.createMessage(response) } }
                    .onErrorResume { error ->
                        logger.warn("Failed to handle Discord /login DM", error)
                        event.message.channel.flatMap {
                            it.createMessage("Unable to create a login link right now. Please try again.")
                        }.onErrorComplete()
                    }
            }
            .doOnError { logger.error("Discord /login DM listener failed", it) }
            .subscribe())
    }

    private fun loginResponse(userId: Long): Mono<String> =
        mono { botLoginService.createLoginLink(userId) }
            .map { "Use this one-time link to log in: $it" }
            .switchIfEmpty(Mono.just("You must be a member of a server shared with this bot to log in."))

    @PreDestroy
    fun stop() = subscriptions.dispose()

    companion object {
        private const val COMMAND_NAME = "login"
    }
}
