package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.Yaml

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val entryRepository: EntryRepository,
    private val discordService: DiscordService,
    private val uploadsService: UploadsService
) {

    suspend fun getRoomsForUser(userId: Long): List<Room> {
        return entryRepository.findByUserId(userId)
            .map { it.roomId }
            .distinct()
            .asFlow()
            .toList()
            .map { roomRepository.findById(it).awaitSingle() }
    }

    suspend fun getAdminGuilds(userId: Long): Flow<GuildInfo> =
        discordService.getAdminGuildsForUser(userId)

    suspend fun getJoinableRooms(userId: Long): List<Room> {
        val result = mutableListOf<Room>()
        discordService.getGuildsForUser(userId).collect { guild ->
            roomRepository.findByGuildId(guild.id).asFlow().collect { room ->
                if (room.id == null) return@collect
                val hasEntries =
                    entryRepository.countByRoomIdAndUserId(room.id, userId).awaitSingle() > 0
                if (!hasEntries) {
                    result.add(room)
                }
            }
        }
        return result
    }

    @Transactional
    suspend fun createRoom(guildId: Long, name: String, userId: Long): Room {
        if (!discordService.isAdminOfGuild(userId, guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }

        val roomExists = roomRepository.existsByGuildIdAndName(guildId, name).awaitSingle()
        if (roomExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A room with this name already exists in this guild")
        }

        return roomRepository.save(Room(guildId = guildId, name = name)).awaitSingle()
    }

    private suspend fun isRoomJoinable(room: Room, userId: Long): Boolean =
        discordService.isMemberOfGuild(userId, room.guildId)

    @Transactional
    suspend fun addEntry(roomId: Long, userId: Long, yamlFilePath: String): Entry {
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!isRoomJoinable(room, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot join this room")
        }

        // Validate YAML file
        extractNameFromYaml(uploadsService.getFile(yamlFilePath))

        return entryRepository.save(
            Entry(
                roomId = roomId,
                userId = userId,
                yamlFilePath = yamlFilePath
            )
        )
            .awaitSingle()
    }

    @Transactional
    suspend fun deleteEntry(entryId: Long, userId: Long, isAdmin: Boolean) {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        if (entry.userId != userId && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's entry")
        }

        entryRepository.deleteById(entryId).awaitSingleOrNull()
    }

    @Transactional
    suspend fun deleteRoom(roomId: Long, userId: Long) {
        val room = roomRepository.findById(roomId).awaitSingle()
        val isAdmin = discordService.isAdminOfGuild(userId, room.guildId)
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        roomRepository.deleteById(roomId).awaitSingleOrNull()
    }

    /**
     * Retrieves room with entries; enforces membership or admin access
     */
    suspend fun getRoom(roomId: Long, userId: Long): RoomWithEntries {
        val room =
            roomRepository.findById(roomId).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!isRoomJoinable(room, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        val isAdmin = isAdminOfGuild(room.guildId, userId)
        val entries = entryRepository.findByRoomId(roomId)
            .asFlow()
            .toList()
            .map { entry ->
                if (entry.id == null) error("Entry ID is null after saving")
                val name = extractNameFromYaml(uploadsService.getFile(entry.yamlFilePath))
                EntryInfo(entry.id, name, discordService.getUserInfo(entry.userId))
            }
        return RoomWithEntries(room, entries, isAdmin)
    }

    suspend fun isAdminOfGuild(guildId: Long, userId: Long): Boolean =
        discordService.isAdminOfGuild(userId, guildId)

    suspend fun getEntry(entryId: Long): Entry? = entryRepository.findById(entryId).awaitSingleOrNull()

    private fun extractNameFromYaml(content: ByteArray): String {
        val trimmedName = Yaml().load<PlayerYaml>(content.inputStream()).name.trim()
        if (trimmedName.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML file: name is empty")
        }
        return trimmedName
    }
}

data class EntryInfo(val id: Long, val name: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: List<EntryInfo>,
    val isAdmin: Boolean
)
