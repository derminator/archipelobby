package com.github.derminator.archipelobby.discord

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.flux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class RealDiscordService(
    private val gatewayDiscordClient: GatewayDiscordClient
) : DiscordService {

    override fun getGuildsForUser(userId: Long): Flux<GuildInfo> = flux {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.collect { guild ->
            val member = guild.getMemberById(userSnowflake)
                .onErrorResume { Mono.empty() }
                .awaitSingleOrNull()
            if (member != null) {
                send(GuildInfo(guild.id.asLong(), guild.name))
            }
        }
    }

    override fun getAdminGuildsForUser(userId: Long): Flux<GuildInfo> = flux {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.collect { guild ->
            val member = guild.getMemberById(userSnowflake)
                .onErrorResume { Mono.empty() }
                .awaitSingleOrNull()
            if (member != null && member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)) {
                send(GuildInfo(guild.id.asLong(), guild.name))
            }
        }
    }

    override fun isMemberOfAnyGuild(userId: Long): Mono<Boolean> =
        gatewayDiscordClient.guilds
            .flatMap { guild ->
                guild.getMemberById(Snowflake.of(userId))
                    .onErrorResume { Mono.empty() }
            }
            .any { true }

    override fun isMemberOfGuild(userId: Long, guildId: Long): Mono<Boolean> =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .flatMap { it.getMemberById(Snowflake.of(userId)) }
            .map { true }
            .onErrorReturn(false)

    override fun isAdminOfGuild(userId: Long, guildId: Long): Mono<Boolean> =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .flatMap { it.getMemberById(Snowflake.of(userId)) }
            .flatMap { it.basePermissions }
            .map { it.contains(Permission.ADMINISTRATOR) }
            .onErrorReturn(false)

    override fun getUserInfo(userId: Long): Mono<UserInfo> =
        gatewayDiscordClient.getUserById(Snowflake.of(userId))
            .map { UserInfo(it.id.asLong(), it.username) }

    override fun getGuildInfo(guildId: Long): Mono<GuildInfo> =
        gatewayDiscordClient.getGuildById(Snowflake.of(guildId))
            .map { GuildInfo(it.id.asLong(), it.name) }
}
