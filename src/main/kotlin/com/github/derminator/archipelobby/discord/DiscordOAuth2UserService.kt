package com.github.derminator.archipelobby.discord

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
@Profile("discord")
class DiscordOAuth2UserService(
    private val discordService: DiscordService
) : DefaultReactiveOAuth2UserService() {

    /**
     * Authenticates user; enforces guild membership; returns user
     */
    override fun loadUser(userRequest: OAuth2UserRequest): Mono<OAuth2User> = mono {
        val user = super.loadUser(userRequest).awaitSingle()
        val userId = user.name.toLong()

        val isMember = discordService.isMemberOfAnyGuild(userId)

        if (!isMember) {
            throw OAuth2AuthenticationException(
                OAuth2Error("access_denied", "User is not a member of any allowed guild", null)
            )
        }

        user
    }
}
