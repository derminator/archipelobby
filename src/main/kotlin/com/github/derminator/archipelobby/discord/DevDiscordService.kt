package com.github.derminator.archipelobby.discord

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "archipelobby.discord.dev")
class DevDiscordProperties {
    var guilds: List<String> = emptyList() // Format: "id:name"
    var adminGuilds: List<String> = emptyList() // Format: "username:guildId"
    var userGuilds: List<String> = emptyList() // Format: "username:guildId"
    var users: List<String> = emptyList()
}

class DevDiscordService(
    private val properties: DevDiscordProperties
) : DiscordService {

    private fun parseGuilds(): List<GuildInfo> = properties.guilds.map {
        val parts = it.split(":", limit = 2)
        GuildInfo(parts[0].toLong(), parts[1])
    }

    private fun parseUsers(): List<UserInfo> = properties.users.mapIndexed { index, it ->
        UserInfo(index.toLong(), it)
    }

    override suspend fun getGuildsForUser(userId: Long): Flow<GuildInfo> {
        val userInfo = getUserInfo(userId)
        val guildIds = (properties.userGuilds + properties.adminGuilds)
            .filter { it.startsWith("${userInfo.username}:") }
            .map { it.substringAfter(":").toLong() }
            .distinct()
        return parseGuilds().filter { guildIds.contains(it.id) }.asFlow()
    }

    override suspend fun getAdminGuildsForUser(userId: Long): Flow<GuildInfo> {
        val userInfo = getUserInfo(userId)
        val adminGuildIds = properties.adminGuilds
            .filter { it.startsWith("${userInfo.username}:") }
            .map { it.substringAfter(":").toLong() }
        return parseGuilds().filter { adminGuildIds.contains(it.id) }.asFlow()
    }

    override suspend fun isMemberOfAnyGuild(userId: Long): Boolean {
        val userInfo = getUserInfo(userId)
        return properties.userGuilds.any { it.startsWith("${userInfo.username}:") } ||
                properties.adminGuilds.any { it.startsWith("${userInfo.username}:") }
    }

    override suspend fun isMemberOfGuild(userId: Long, guildId: Long): Boolean {
        val userInfo = getUserInfo(userId)
        return properties.userGuilds.contains("${userInfo.username}:$guildId") ||
                properties.adminGuilds.contains("${userInfo.username}:$guildId")
    }

    override suspend fun isAdminOfGuild(userId: Long, guildId: Long): Boolean {
        val userInfo = getUserInfo(userId)
        return properties.adminGuilds.contains("${userInfo.username}:$guildId")
    }

    override suspend fun getUserInfo(userId: Long): UserInfo =
        parseUsers().singleOrNull { it.id == userId }
            ?: throw IllegalArgumentException("User with ID $userId not found in dev properties")

    override suspend fun getGuildInfo(guildId: Long): GuildInfo =
        parseGuilds().single { it.id == guildId }
}
