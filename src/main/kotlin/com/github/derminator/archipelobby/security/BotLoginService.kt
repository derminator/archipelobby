package com.github.derminator.archipelobby.security

import com.github.derminator.archipelobby.discord.DiscordService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Component
@ConfigurationProperties(prefix = "archipelobby.discord.bot-login")
class BotLoginProperties {
    var baseUrl: String = "http://localhost:8080"
    var tokenValidity: Duration = Duration.ofMinutes(5)
}

@Service
class BotLoginService(
    private val discordService: DiscordService,
    private val properties: BotLoginProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val tokenGenerator: () -> String = DEFAULT_TOKEN_GENERATOR
) {
    private data class PendingLogin(val principal: DiscordPrincipal, val expiresAt: Instant)

    private val pendingLogins = ConcurrentHashMap<String, PendingLogin>()

    suspend fun createLoginLink(userId: Long): String? {
        if (!discordService.isMemberOfAnyGuild(userId)) return null

        val user = discordService.getUserInfo(userId)
        val now = clock.instant()
        pendingLogins.entries.removeIf { it.value.expiresAt <= now }

        val pendingLogin = PendingLogin(
            DiscordPrincipal(user.id, user.username),
            now.plus(properties.tokenValidity)
        )
        val token = storeWithUniqueToken(pendingLogin)
        return "${properties.baseUrl.trimEnd('/')}/login/bot?token=$token"
    }

    fun consume(token: String): DiscordPrincipal? {
        val pendingLogin = pendingLogins.remove(token) ?: return null
        return pendingLogin.principal.takeIf { pendingLogin.expiresAt > clock.instant() }
    }

    private fun storeWithUniqueToken(pendingLogin: PendingLogin): String {
        repeat(10) {
            val token = tokenGenerator()
            if (pendingLogins.putIfAbsent(token, pendingLogin) == null) return token
        }
        throw IllegalStateException("Could not generate a unique bot login token")
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private val DEFAULT_TOKEN_GENERATOR = {
            ByteArray(32).also(SECURE_RANDOM::nextBytes).let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
        }
    }
}
