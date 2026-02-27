package com.github.derminator.archipelobby.security

import org.springframework.security.core.Authentication
import java.security.Principal

/**
 * Custom principal that provides consistent access to Discord user information
 * across both OAuth2 (RealDiscordService) and dev mode (DevDiscordService).
 */
data class DiscordPrincipal(
    val userId: Long,
    val username: String
) : Principal {
    override fun getName(): String = userId.toString()
}

val Principal.asDiscordPrincipal: DiscordPrincipal
    get() {
        if (this is DiscordPrincipal) return this
        if (this is Authentication && this.principal is DiscordPrincipal) return this.principal as DiscordPrincipal
        throw IllegalStateException("Principal is not a DiscordPrincipal: ${this::class.simpleName}")
    }
