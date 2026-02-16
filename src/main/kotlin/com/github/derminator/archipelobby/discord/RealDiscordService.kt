package com.github.derminator.archipelobby.discord

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.rest.util.Permission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import reactor.core.publisher.Mono

class RealDiscordService(
    private val gatewayDiscordClient: GatewayDiscordClient
) : DiscordService {

    override suspend fun getGuildsForUser(userId: Long): Flow<GuildInfo> = flow {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.asFlow().collect { guild ->
            val member = guild.getMemberById(userSnowflake)
                .onErrorResume { Mono.empty() }
                .awaitSingleOrNull()
            if (member != null) {
                emit(GuildInfo(guild.id.asLong(), guild.name))
            }
        }
    }

    override suspend fun getAdminGuildsForUser(userId: Long): Flow<GuildInfo> = flow {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.asFlow().collect { guild ->
            val member = guild.getMemberById(userSnowflake)
                .onErrorResume { Mono.empty() }
                .awaitSingleOrNull()
            if (member != null && member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)) {
                emit(GuildInfo(guild.id.asLong(), guild.name))
            }
        }
    }

    override suspend fun isMemberOfAnyGuild(userId: Long): Boolean =
        gatewayDiscordClient.guilds
            .flatMap { guild ->
                guild.getMemberById(Snowflake.of(userId))
                    .onErrorResume { Mono.empty() }
            }
            .any { true }
            .awaitSingle()

    override suspend fun isMemberOfGuild(userId: Long, guildId: Long): Boolean =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .flatMap { it.getMemberById(Snowflake.of(userId)) }
            .map { true }
            .onErrorReturn(false)
            .awaitSingle()

    override suspend fun isAdminOfGuild(userId: Long, guildId: Long): Boolean =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .flatMap { it.getMemberById(Snowflake.of(userId)) }
            .flatMap { it.basePermissions }
            .map { it.contains(Permission.ADMINISTRATOR) }
            .onErrorReturn(false)
            .awaitSingle()

    override suspend fun getUserInfo(userId: Long): UserInfo =
        gatewayDiscordClient.getUserById(Snowflake.of(userId))
            .map { UserInfo(it.id.asLong(), it.username) }
            .awaitSingle()

    override suspend fun getGuildInfo(guildId: Long): GuildInfo =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .map { GuildInfo(it.id.asLong(), it.name) }
            .awaitSingle()
}
