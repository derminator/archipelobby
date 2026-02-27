package com.github.derminator.archipelobby.security

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Converts standard Spring Security authentication objects into our custom DiscordPrincipal.
 * Works for both OAuth2 authentication (discord profile) and form login (dev mode).
 */
@Component
class DiscordPrincipalConverter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> = mono {
        val authentication = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication

        if (authentication != null && authentication.isAuthenticated) {
            val principal = when (authentication) {
                is OAuth2AuthenticationToken -> {
                    // OAuth2 (discord profile): extract user ID and username
                    val oauth2User = authentication.principal ?: return@mono null
                    val userId = oauth2User.name.toLong()
                    val username = oauth2User.attributes["username"] as? String ?: "Unknown"
                    DiscordPrincipal(userId, username)
                }

                is UsernamePasswordAuthenticationToken -> {
                    // Dev mode (form login): username is the user ID
                    val name = authentication.name
                    val userId = name.toLongOrNull()
                    if (userId != null) {
                        val username = "DevUser_$userId"
                        DiscordPrincipal(userId, username)
                    } else {
                        null
                    }
                }

                else -> null
            }

            if (principal != null) {
                // Create new authentication with DiscordPrincipal
                val newAuth = UsernamePasswordAuthenticationToken(
                    principal,
                    authentication.credentials,
                    authentication.authorities
                )

                // If it's already a UsernamePasswordAuthenticationToken with DiscordPrincipal, avoid double wrap
                if (authentication is UsernamePasswordAuthenticationToken && authentication.principal is DiscordPrincipal) {
                    chain.filter(exchange).awaitSingleOrNull()
                } else {
                    chain.filter(exchange)
                        .contextWrite(
                            ReactiveSecurityContextHolder.withSecurityContext(
                                Mono.just(
                                    ReactiveSecurityContextHolder.getContext().awaitSingle().apply {
                                        this.authentication = newAuth
                                    })
                            )
                        )
                        .awaitSingleOrNull()
                }
            } else {
                chain.filter(exchange).awaitSingleOrNull()
            }
        } else {
            chain.filter(exchange).awaitSingleOrNull()
        }
        null
    }
}
