package com.github.derminator.archipelobby.security

import com.github.derminator.archipelobby.discord.DevDiscordProperties
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Common base for converting standard Spring Security authentication objects into our custom DiscordPrincipal.
 */
abstract class AbstractDiscordPrincipalConverter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> = mono {
        val authentication = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication

        if (authentication != null && authentication.isAuthenticated) {
            val principal = getPrincipal(authentication)

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

    abstract fun getPrincipal(authentication: Authentication): DiscordPrincipal?
}

/**
 * Handles OAuth2 authentication from Discord.
 */
@Component
@Profile("discord")
class RealDiscordPrincipalConverter : AbstractDiscordPrincipalConverter() {
    override fun getPrincipal(authentication: Authentication): DiscordPrincipal? {
        if (authentication !is OAuth2AuthenticationToken) return null

        val oauth2User = authentication.principal ?: return null
        val userId = oauth2User.name.toLong()
        val username = oauth2User.attributes["username"] as? String ?: "Unknown"
        return DiscordPrincipal(userId, username)
    }
}

/**
 * Handles form login for dev mode.
 * Uses DevDiscordProperties to match usernames to their index-based IDs.
 */
@Component
@Profile("!discord")
class DevDiscordPrincipalConverter(private val properties: DevDiscordProperties) : AbstractDiscordPrincipalConverter() {
    override fun getPrincipal(authentication: Authentication): DiscordPrincipal? {
        if (authentication !is UsernamePasswordAuthenticationToken) return null
        if (authentication.principal is DiscordPrincipal) return null // Already converted

        val username = authentication.name
        val userIndex = properties.users.indexOf(username)
        if (userIndex != -1) {
            return DiscordPrincipal(userIndex.toLong(), username)
        }
        return null
    }
}
