package com.github.derminator.archipelobby.discord

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class DiscordOAuth2UserService(
    private val gatewayDiscordClient: GatewayDiscordClient
) : DefaultReactiveOAuth2UserService() {

    override fun loadUser(userRequest: OAuth2UserRequest): Mono<OAuth2User> = mono {
        val user = super.loadUser(userRequest).awaitSingle()
        val userId = user.name ?: throw OAuth2AuthenticationException(
            OAuth2Error("missing_id", "User ID is missing from Discord response", null)
        )
        val snowflake = Snowflake.of(userId)

        val isMember = gatewayDiscordClient.guilds
            .flatMap { guild ->
                guild.getMemberById(snowflake)
                    .onErrorResume { Mono.empty() }
            }
            .any { true }
            .awaitSingle()

        if (!isMember) {
            throw OAuth2AuthenticationException(
                OAuth2Error("access_denied", "User is not a member of any allowed guild", null)
            )
        }

        user
    }
}
