package com.github.derminator.archipelobby.discord

import kotlinx.coroutines.flow.Flow

interface DiscordService {
    suspend fun getGuildsForUser(userId: Long): Flow<GuildInfo>
    suspend fun getAdminGuildsForUser(userId: Long): Flow<GuildInfo>
    suspend fun isMemberOfAnyGuild(userId: Long): Boolean
    suspend fun isMemberOfGuild(userId: Long, guildId: Long): Boolean
    suspend fun isAdminOfGuild(userId: Long, guildId: Long): Boolean
    suspend fun getUserInfo(userId: Long): UserInfo
    suspend fun getGuildInfo(guildId: Long): GuildInfo
}

data class GuildInfo(val id: Long, val name: String)
data class UserInfo(val id: Long, val username: String)
