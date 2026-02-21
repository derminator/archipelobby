package com.github.derminator.archipelobby.security

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
