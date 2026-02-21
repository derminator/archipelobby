package com.github.derminator.archipelobby.security

import kotlinx.coroutines.reactor.mono
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.OAuth2User
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

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap { securityContext ->
                val authentication = securityContext.authentication

                if (authentication != null && authentication.isAuthenticated) {
                    val principal = when (authentication) {
                        is OAuth2AuthenticationToken -> {
                            // OAuth2 (discord profile): extract user ID and username
                            val oauth2User = authentication.principal as OAuth2User
                            val userId = oauth2User.name.toLong()
                            val username = oauth2User.attributes["username"] as? String ?: "Unknown"
                            DiscordPrincipal(userId, username)
                        }

                        is UsernamePasswordAuthenticationToken -> {
                            // Dev mode (form login): username is the user ID
                            val userId = authentication.name.toLong()
                            val username = "DevUser_$userId"
                            DiscordPrincipal(userId, username)
                        }

                        else -> {
                            // Fallback: try to parse name as user ID
                            val userId = authentication.name.toLongOrNull() ?: 0L
                            val username = "User_$userId"
                            DiscordPrincipal(userId, username)
                        }
                    }

                    // Create new authentication with DiscordPrincipal
                    val newAuth = UsernamePasswordAuthenticationToken(
                        principal,
                        authentication.credentials,
                        authentication.authorities
                    )

                    securityContext.authentication = newAuth

                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(mono { securityContext }))
                } else {
                    chain.filter(exchange)
                }
            }
            .switchIfEmpty(chain.filter(exchange))
    }
}
