package com.github.derminator.archipelobby.discord

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User

@Configuration
@Profile("!discord")
class DevDiscordConfiguration(
    private val properties: DevDiscordProperties
) {
    @Bean
    fun discordService(): DiscordService =
        DevDiscordService(properties)

    @Bean
    fun userDetailsService(): MapReactiveUserDetailsService =
        MapReactiveUserDetailsService(properties.users.map {
            @Suppress("DEPRECATION")
            User.withDefaultPasswordEncoder()
                .username(it)
                .password("password")
                .build()
        })
}
