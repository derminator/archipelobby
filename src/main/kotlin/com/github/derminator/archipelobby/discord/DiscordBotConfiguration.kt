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
    fun gatewayDiscordClient(@Value("\${DISCORD_BOT_TOKEN}") token: String): GatewayDiscordClient =
        DiscordClientBuilder.create(token)
            .build()
            .login()
            .block() ?: throw IllegalStateException("Failed to connect to Discord")

    @Bean
    fun discordService(gatewayDiscordClient: GatewayDiscordClient): DiscordService =
        RealDiscordService(gatewayDiscordClient)
}
