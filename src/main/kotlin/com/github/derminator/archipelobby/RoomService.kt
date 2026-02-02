package com.github.derminator.archipelobby

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val gatewayDiscordClient: GatewayDiscordClient
) {

    fun getRoomsForUser(userId: Long): Flux<Room> = flux {
        roomMemberRepository.findByUserId(userId).collect {
            val room = roomRepository.findById(it.roomId).awaitSingle()
            send(room)
        }
    }

    fun getAdminGuilds(userId: Long): Flux<GuildInfo> = flux {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.collect { guid ->
            val member = guid.getMemberById(userSnowflake).awaitSingleOrNull()
            if (member != null && member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)) {
                send(GuildInfo(guid.id.asLong(), guid.name))
            }
        }
    }

    fun getJoinableRooms(userId: Long): Flux<Room> = flux {
        val userSnowflake = Snowflake.of(userId)
        gatewayDiscordClient.guilds.collect { guid ->
            val member = guid.getMemberById(userSnowflake).awaitSingleOrNull()
            if (member != null) {
                roomRepository.findByGuildId(guid.id.asLong()).collect { room ->
                    val isMember =
                        roomMemberRepository.findByRoomIdAndUserId(room.id!!, userId).awaitSingleOrNull() != null
                    if (!isMember) {
                        send(room)
                    }
                }
            }
        }
    }

    @Transactional
    fun createRoom(guildId: Long, name: String, userId: Long): Mono<Room> = mono {
        val userSnowflake = Snowflake.of(userId)
        val guid = gatewayDiscordClient.getGuildById(Snowflake.of(guildId)).awaitSingle()
        val member = guid.getMemberById(userSnowflake).awaitSingle()
        if (!member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        val room = roomRepository.save(Room(guildId = guildId, name = name)).awaitSingle()
        val roomMember = room.addMember(userId)
        roomMemberRepository.save(roomMember).awaitSingle()
        room
    }

    @Transactional
    fun joinRoom(roomId: Long, userId: Long): Mono<RoomMember> = mono {
        val room = roomRepository.findById(roomId).awaitSingle()
        val guid = gatewayDiscordClient.getGuildById(Snowflake.of(room.guildId)).awaitSingle()
        guid.getMemberById(Snowflake.of(userId)).awaitSingle()
        roomMemberRepository.findByRoomIdAndUserId(roomId, userId).awaitSingleOrNull() ?: roomMemberRepository.save(
            RoomMember(roomId = roomId, userId = userId)
        ).awaitSingle()
    }

    @Transactional
    fun leaveRoom(roomId: Long, userId: Long): Mono<Void> {
        return roomMemberRepository.deleteByRoomIdAndUserId(roomId, userId)
    }

    @Transactional
    fun deleteRoom(roomId: Long, userId: Long): Mono<Void> = mono {
        val room = roomRepository.findById(roomId).awaitSingle()
        val guild = gatewayDiscordClient.getGuildById(Snowflake.of(room.guildId)).awaitSingle()
        val member = guild.getMemberById(Snowflake.of(userId)).awaitSingle()
        val isAdmin = member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        roomRepository.deleteById(roomId).awaitSingleOrNull()
    }

    fun getRoom(roomId: Long, userId: Long): Mono<RoomWithMembers> = mono {
        val room =
            roomRepository.findById(roomId).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val isMember = roomMemberRepository.findByRoomIdAndUserId(roomId, userId).awaitSingleOrNull() != null
        val isAdmin = isAdminOfGuild(room.guildId, userId).awaitSingle()
        if (!isMember && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        val members = roomMemberRepository.findByRoomId(roomId)
            .flatMap { gatewayDiscordClient.getUserById(Snowflake.of(it.userId)) }
            .map { UserInfo(it.id.asLong(), it.username) }
            .collectList()
            .awaitSingle()
        RoomWithMembers(room, members, isAdmin, isMember)
    }

    fun isAdminOfGuild(guildId: Long, userId: Long): Mono<Boolean> = mono {
        val guid = gatewayDiscordClient.getGuildById(Snowflake.of(guildId)).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val member = guid.getMemberById(Snowflake.of(userId)).awaitSingleOrNull() ?: return@mono false
        member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)
    }
}

data class GuildInfo(val id: Long, val name: String)
data class UserInfo(val id: Long, val username: String)
data class RoomWithMembers(val room: Room, val members: List<UserInfo>, val isAdmin: Boolean, val isMember: Boolean)
