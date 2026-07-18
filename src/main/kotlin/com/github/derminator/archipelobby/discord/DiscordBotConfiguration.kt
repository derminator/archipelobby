package com.github.derminator.archipelobby.discord

import discord4j.core.DiscordClientBuilder
import discord4j.core.GatewayDiscordClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration

@Configuration
@Profile("discord")
class DiscordBotConfiguration {

    @Bean
    fun discordGatewayProvider(@Value("\${DISCORD_BOT_TOKEN}") token: String): DiscordGatewayProvider =
        DiscordGatewayProvider(token)

    @Bean
    fun gatewayDiscordClient(discordGatewayProvider: DiscordGatewayProvider): GatewayDiscordClient =
        discordGatewayProvider.getConnectedClient()

    @Bean
    fun discordService(discordGatewayProvider: DiscordGatewayProvider): DiscordService =
        RealDiscordService(discordGatewayProvider)
}

class DiscordGatewayProvider(
    private val token: String,
    private val logoutTimeout: Duration = Duration.ofSeconds(2),
    private val login: (String) -> GatewayDiscordClient = { loginToken ->
        DiscordClientBuilder.create(loginToken)
            .build()
            .login()
            .block() ?: throw IllegalStateException("Failed to connect to Discord")
    }
) {
    private val logger = LoggerFactory.getLogger(DiscordGatewayProvider::class.java)

    @Volatile
    private var gatewayDiscordClient: GatewayDiscordClient? = null

    @Synchronized
    fun getConnectedClient(): GatewayDiscordClient {
        val existing = gatewayDiscordClient
        if (existing != null && existing.isConnected) {
            return existing
        }

        gatewayDiscordClient = null
        if (existing != null) {
            cleanupStaleClient(existing)
        }

        val connected = login(token)
        gatewayDiscordClient = connected
        return connected
    }

    private fun cleanupStaleClient(existing: GatewayDiscordClient) {
        try {
            existing.logout()
                .timeout(logoutTimeout)
                .doOnError { ex ->
                    logger.warn("Failed to logout disconnected Discord gateway client before reconnecting; continuing reconnect", ex)
                }
                .onErrorComplete()
                .block()
        } catch (ex: Exception) {
            logger.warn("Failed to logout disconnected Discord gateway client before reconnecting; continuing reconnect", ex)
        }
    }
}
