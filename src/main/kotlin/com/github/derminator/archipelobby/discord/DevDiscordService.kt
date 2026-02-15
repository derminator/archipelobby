package com.github.derminator.archipelobby.discord

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
@ConfigurationProperties(prefix = "archipelobby.discord.dev")
class DevDiscordProperties {
    var guilds: List<String> = emptyList() // Format: "id:name"
    var adminGuilds: List<Long> = emptyList()
    var users: List<String> = emptyList() // Format: "id:username"
}

class DevDiscordService(
    private val properties: DevDiscordProperties
) : DiscordService {

    private fun parseGuilds(): List<GuildInfo> = properties.guilds.map {
        val parts = it.split(":", limit = 2)
        GuildInfo(parts[0].toLong(), parts[1])
    }

    private fun parseUsers(): List<UserInfo> = properties.users.map {
        val parts = it.split(":", limit = 2)
        UserInfo(parts[0].toLong(), parts[1])
    }

    override fun getGuildsForUser(userId: Long): Flux<GuildInfo> =
        Flux.fromIterable(parseGuilds())

    override fun getAdminGuildsForUser(userId: Long): Flux<GuildInfo> =
        Flux.fromIterable(parseGuilds().filter { properties.adminGuilds.contains(it.id) })

    override fun isMemberOfAnyGuild(userId: Long): Mono<Boolean> =
        Mono.just(parseGuilds().isNotEmpty())

    override fun isMemberOfGuild(userId: Long, guildId: Long): Mono<Boolean> =
        Mono.just(parseGuilds().any { it.id == guildId })

    override fun isAdminOfGuild(userId: Long, guildId: Long): Mono<Boolean> =
        Mono.just(properties.adminGuilds.contains(guildId))

    override fun getUserInfo(userId: Long): Mono<UserInfo> =
        Mono.justOrEmpty(parseUsers().find { it.id == userId })
            .switchIfEmpty(Mono.just(UserInfo(userId, "DevUser_$userId")))

    override fun getGuildInfo(guildId: Long): Mono<GuildInfo> =
        Mono.justOrEmpty(parseGuilds().find { it.id == guildId })
            .switchIfEmpty(Mono.just(GuildInfo(guildId, "DevGuild_$guildId")))
}
