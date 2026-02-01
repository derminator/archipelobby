package com.github.derminator.archipelobby

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
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

    override fun loadUser(userRequest: OAuth2UserRequest): Mono<OAuth2User> {
        return super.loadUser(userRequest).flatMap { user ->
            val userId = user.name ?: return@flatMap Mono.error(
                OAuth2AuthenticationException(
                    OAuth2Error(
                        "missing_id",
                        "User ID is missing from Discord response",
                        null
                    )
                )
            )
            val snowflake = Snowflake.of(userId)

            gatewayDiscordClient.guilds
                .flatMap { guild ->
                    guild.getMemberById(snowflake)
                        .onErrorResume { Mono.empty() }
                }
                .any { true }
                .flatMap { isMember ->
                    if (isMember) {
                        Mono.just(user)
                    } else {
                        Mono.error(
                            OAuth2AuthenticationException(
                                OAuth2Error("access_denied", "User is not a member of any allowed guild", null)
                            )
                        )
                    }
                }
        }
    }
}
