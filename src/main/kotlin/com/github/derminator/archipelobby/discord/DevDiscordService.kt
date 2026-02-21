package com.github.derminator.archipelobby.discord

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

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

    override suspend fun getGuildsForUser(userId: Long): Flow<GuildInfo> =
        parseGuilds().asFlow()

    override suspend fun getAdminGuildsForUser(userId: Long): Flow<GuildInfo> =
        parseGuilds().filter { properties.adminGuilds.contains(it.id) }.asFlow()

    override suspend fun isMemberOfAnyGuild(userId: Long): Boolean =
        parseGuilds().isNotEmpty()

    override suspend fun isMemberOfGuild(userId: Long, guildId: Long): Boolean =
        parseGuilds().any { it.id == guildId }

    override suspend fun isAdminOfGuild(userId: Long, guildId: Long): Boolean =
        properties.adminGuilds.contains(guildId)

    override suspend fun getUserInfo(userId: Long): UserInfo =
        parseUsers().find { it.id == userId } ?: UserInfo(userId, "DevUser_$userId")

    override suspend fun getGuildInfo(guildId: Long): GuildInfo =
        parseGuilds().find { it.id == guildId } ?: GuildInfo(guildId, "DevGuild_$guildId")
}
