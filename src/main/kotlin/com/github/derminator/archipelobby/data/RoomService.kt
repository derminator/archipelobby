package com.github.derminator.archipelobby.data

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
    private val entryRepository: EntryRepository,
    private val gatewayDiscordClient: GatewayDiscordClient
) {

    fun getRoomsForUser(userId: Long): Flux<Room> = flux {
        entryRepository.findByUserId(userId)
            .map { it.roomId }
            .distinct()
            .collect {
                val room = roomRepository.findById(it).awaitSingle()
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
                    if (room.id == null) return@collect
                    val hasEntries =
                        entryRepository.countByRoomIdAndUserId(room.id, userId).awaitSingle() > 0
                    if (!hasEntries) {
                        send(room)
                    }
                }
            }
        }
    }

    @Transactional
    fun createRoom(guildId: Long, name: String, userId: Long, entryName: String): Mono<Room> = mono {
        val userSnowflake = Snowflake.of(userId)
        val guid = gatewayDiscordClient.getGuildById(Snowflake.of(guildId)).awaitSingle()
        val member = guid.getMemberById(userSnowflake).awaitSingle()
        if (!member.basePermissions.awaitSingle().contains(Permission.ADMINISTRATOR)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        if (entryName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry name cannot be empty")
        }

        val roomExists = roomRepository.existsByGuildIdAndName(guildId, name).awaitSingle()
        if (roomExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A room with this name already exists in this guild")
        }

        val room = roomRepository.save(Room(guildId = guildId, name = name)).awaitSingle()
        if (room.id == null) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Room ID is null after saving")
        }
        entryRepository.save(Entry(roomId = room.id, userId = userId, name = entryName)).awaitSingle()
        room
    }

    private fun isRoomJoinable(room: Room, userId: Long): Mono<Boolean> = mono {
        val guild = gatewayDiscordClient.getGuildById(Snowflake.of(room.guildId)).awaitSingle()
        val member = guild.getMemberById(Snowflake.of(userId)).awaitSingleOrNull()
        member != null
    }

    @Transactional
    fun addEntry(roomId: Long, userId: Long, entryName: String): Mono<Entry> = mono {
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!isRoomJoinable(room, userId).awaitSingle()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot join this room")
        }

        if (entryName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry name cannot be empty")
        }

        val nameExists = entryRepository.existsByRoomIdAndName(roomId, entryName).awaitSingle()
        if (nameExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An entry with this name already exists in this room")
        }

        entryRepository.save(Entry(roomId = roomId, userId = userId, name = entryName)).awaitSingle()
    }

    @Transactional
    fun deleteEntry(entryId: Long, userId: Long, isAdmin: Boolean): Mono<Void> = mono {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        if (entry.userId != userId && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's entry")
        }

        entryRepository.deleteById(entryId).awaitSingleOrNull()
    }

    @Transactional
    fun renameEntry(entryId: Long, userId: Long, newName: String): Mono<Entry> = mono {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        if (entry.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot rename another user's entry")
        }

        if (newName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry name cannot be empty")
        }

        if (entry.name != newName) {
            val nameExists = entryRepository.existsByRoomIdAndName(entry.roomId, newName).awaitSingle()
            if (nameExists) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An entry with this name already exists in this room"
                )
            }
        }

        entryRepository.save(entry.copy(name = newName)).awaitSingle()
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

    /**
     * Retrieves room with entries; enforces membership or admin access
     */
    fun getRoom(roomId: Long, userId: Long): Mono<RoomWithEntries> = mono {
        val room =
            roomRepository.findById(roomId).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!isRoomJoinable(room, userId).awaitSingle()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        val isAdmin = isAdminOfGuild(room.guildId, userId).awaitSingle()
        val isMember = entryRepository.countByRoomIdAndUserId(roomId, userId).awaitSingle() > 0
        val entries = entryRepository.findByRoomId(roomId)
            .flatMap { entry ->
                if (entry.id == null) error("Entry ID is null after saving")
                gatewayDiscordClient.getUserById(Snowflake.of(entry.userId))
                    .map { EntryInfo(entry.id, entry.name, UserInfo(it.id.asLong(), it.username)) }
            }
            .collectList()
            .awaitSingle()
        RoomWithEntries(room, entries, isAdmin, isMember)
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
data class EntryInfo(val id: Long, val name: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: List<EntryInfo>,
    val isAdmin: Boolean,
    val isMember: Boolean
)
