package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
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
    private val discordService: DiscordService
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

    fun getAdminGuilds(userId: Long): Flux<GuildInfo> =
        discordService.getAdminGuildsForUser(userId)

    fun getJoinableRooms(userId: Long): Flux<Room> = flux {
        discordService.getGuildsForUser(userId).collect { guild ->
            roomRepository.findByGuildId(guild.id).collect { room ->
                if (room.id == null) return@collect
                val hasEntries =
                    entryRepository.countByRoomIdAndUserId(room.id, userId).awaitSingle() > 0
                if (!hasEntries) {
                    send(room)
                }
            }
        }
    }

    @Transactional
    fun createRoom(guildId: Long, name: String, userId: Long): Mono<Room> = mono {
        if (!discordService.isAdminOfGuild(userId, guildId).awaitSingle()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }

        val roomExists = roomRepository.existsByGuildIdAndName(guildId, name).awaitSingle()
        if (roomExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A room with this name already exists in this guild")
        }

        roomRepository.save(Room(guildId = guildId, name = name)).awaitSingle()
    }

    private fun isRoomJoinable(room: Room, userId: Long): Mono<Boolean> =
        discordService.isMemberOfGuild(userId, room.guildId)

    @Transactional
    fun addEntry(roomId: Long, userId: Long, entryName: String, yamlFilePath: String): Mono<Entry> = mono {
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

        entryRepository.save(Entry(roomId = roomId, userId = userId, name = entryName, yamlFilePath = yamlFilePath))
            .awaitSingle()
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
        val isAdmin = discordService.isAdminOfGuild(userId, room.guildId).awaitSingle()
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
        val entries = entryRepository.findByRoomId(roomId)
            .flatMap { entry ->
                if (entry.id == null) error("Entry ID is null after saving")
                discordService.getUserInfo(entry.userId)
                    .map { EntryInfo(entry.id, entry.name, it) }
            }
            .collectList()
            .awaitSingle()
        RoomWithEntries(room, entries, isAdmin)
    }

    fun isAdminOfGuild(guildId: Long, userId: Long): Mono<Boolean> =
        discordService.isAdminOfGuild(userId, guildId)

    fun getEntry(entryId: Long): Mono<Entry> = entryRepository.findById(entryId)
}

data class EntryInfo(val id: Long, val name: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: List<EntryInfo>,
    val isAdmin: Boolean
)
