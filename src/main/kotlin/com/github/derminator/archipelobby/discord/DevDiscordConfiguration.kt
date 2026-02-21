package com.github.derminator.archipelobby.discord

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User

@Configuration
@Profile("!discord")
class DevDiscordConfiguration {
    @Bean
    fun discordService(properties: DevDiscordProperties): DiscordService =
        DevDiscordService(properties)

    @Bean
    fun userDetailsService(): MapReactiveUserDetailsService {
        @Suppress("DEPRECATION")
        val user = User.withDefaultPasswordEncoder()
            .username("123456789")
            .password("password")
            .roles("USER")
            .build()
        return MapReactiveUserDetailsService(user)
    }
}
