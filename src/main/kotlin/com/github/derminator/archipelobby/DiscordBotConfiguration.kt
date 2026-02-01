package com.github.derminator.archipelobby

import discord4j.core.DiscordClientBuilder
import discord4j.core.GatewayDiscordClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DiscordBotConfiguration {

    @Bean
    @ConditionalOnProperty("DISCORD_BOT_TOKEN")
    fun gatewayDiscordClient(@Value("\${DISCORD_BOT_TOKEN}") token: String): GatewayDiscordClient {
        return DiscordClientBuilder.create(token)
            .build()
            .login()
            .block() ?: throw IllegalStateException("Failed to connect to Discord")
    }
}
