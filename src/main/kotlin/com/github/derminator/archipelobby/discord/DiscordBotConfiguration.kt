package com.github.derminator.archipelobby.discord

import discord4j.core.DiscordClientBuilder
import discord4j.core.GatewayDiscordClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

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

class DiscordGatewayProvider(private val token: String) {
    @Volatile
    private var gatewayDiscordClient: GatewayDiscordClient? = null

    @Synchronized
    fun getConnectedClient(): GatewayDiscordClient {
        val existing = gatewayDiscordClient
        if (existing != null && existing.isConnected) {
            return existing
        }

        existing?.logout()?.block()
        val connected = DiscordClientBuilder.create(token)
            .build()
            .login()
            .block() ?: throw IllegalStateException("Failed to connect to Discord")
        gatewayDiscordClient = connected
        return connected
    }
}
