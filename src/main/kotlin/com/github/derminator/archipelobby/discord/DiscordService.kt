package com.github.derminator.archipelobby.discord

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface DiscordService {
    fun getGuildsForUser(userId: Long): Flux<GuildInfo>
    fun getAdminGuildsForUser(userId: Long): Flux<GuildInfo>
    fun isMemberOfAnyGuild(userId: Long): Mono<Boolean>
    fun isMemberOfGuild(userId: Long, guildId: Long): Mono<Boolean>
    fun isAdminOfGuild(userId: Long, guildId: Long): Mono<Boolean>
    fun getUserInfo(userId: Long): Mono<UserInfo>
    fun getGuildInfo(guildId: Long): Mono<GuildInfo>
}

data class GuildInfo(val id: Long, val name: String)
data class UserInfo(val id: Long, val username: String)
