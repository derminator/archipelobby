package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import com.github.derminator.archipelobby.game.GameService
import com.github.derminator.archipelobby.generation.GenerationService
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import kotlin.io.path.writeBytes

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val entryRepository: EntryRepository,
    private val apworldRepository: ApworldRepository,
    private val discordService: DiscordService,
    private val gameService: GameService,
    private val uploadsService: UploadsService,
    private val generationService: GenerationService
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

    private suspend fun checkRoomNotLocked(roomId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (room.generatedWorldPath != null) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "This room is locked: the game has already been generated. Ask an admin to delete the generated world to re-open entry editing."
            )
        }
    }

    @Transactional
    suspend fun addEntry(roomId: Long, userId: Long, entryName: String, game: String, yamlFilePath: String): Entry {
        checkRoomNotLocked(roomId)

        val room = roomRepository.findById(roomId).awaitSingle()
        if (!isRoomJoinable(room, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot join this room")
        }

        if (entryName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry name cannot be empty")
        }

        val nameExists = entryRepository.existsByRoomIdAndName(roomId, entryName).awaitSingle()
        if (nameExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An entry with this name already exists in this room")
        }

        validateGameForRoom(roomId, game)

        return entryRepository.save(
            Entry(
                roomId = roomId,
                userId = userId,
                name = entryName,
                game = game,
                yamlFilePath = yamlFilePath
            )
        ).awaitSingle()
    }

    private suspend fun validateGameForRoom(roomId: Long, gameName: String) {
        if (gameService.isBuiltinGame(gameName)) return
        val apworldExists = apworldRepository.existsByRoomIdAndGameName(roomId, gameName).awaitSingle()
        if (!apworldExists) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "'$gameName' is not a built-in Archipelago game. Upload an .apworld file for it first."
            )
        }
    }

    @Transactional
    suspend fun addApworld(roomId: Long, userId: Long, gameName: String, filePath: String): Apworld {
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!isRoomJoinable(room, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot join this room")
        }

        val apworldExists = apworldRepository.existsByRoomIdAndGameName(roomId, gameName).awaitSingle()
        if (apworldExists) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An .apworld for '$gameName' has already been uploaded to this room"
            )
        }

        return apworldRepository.save(
            Apworld(roomId = roomId, userId = userId, gameName = gameName, filePath = filePath)
        ).awaitSingle()
    }

    @Transactional
    suspend fun deleteEntry(entryId: Long, userId: Long, isAdmin: Boolean) {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        checkRoomNotLocked(entry.roomId)

        if (entry.userId != userId && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's entry")
        }

        entryRepository.deleteById(entryId).awaitSingleOrNull()
    }

    @Transactional
    suspend fun renameEntry(entryId: Long, userId: Long, newName: String): Entry {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        checkRoomNotLocked(entry.roomId)

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

        return entryRepository.save(entry.copy(name = newName)).awaitSingle()
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
     * Generates the Archipelago world for the room using the GraalPy Truffle bridge.
     * Only admins can generate. Locks the room after generation.
     */
    @Transactional
    suspend fun generateWorld(roomId: Long, userId: Long): Room {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can generate the game")
        }

        if (room.generatedWorldPath != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A world has already been generated for this room")
        }

        val entries = entryRepository.findByRoomId(roomId).asFlow().toList()
        if (entries.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot generate a world with no entries")
        }

        val playerFilesDir = withContext(Dispatchers.IO) { Files.createTempDirectory("archipelobby-yamls-") }
        val outputDir = withContext(Dispatchers.IO) { Files.createTempDirectory("archipelobby-output-") }

        try {
            // Write all YAML entries to the temp player files directory
            withContext(Dispatchers.IO) {
                for (entry in entries) {
                    val yamlBytes = uploadsService.getFile(entry.yamlFilePath)
                    playerFilesDir.resolve("${entry.name}.yaml").writeBytes(yamlBytes)
                }
            }

            // Run the generator via GraalPy Truffle bridge
            val generatedFile = withContext(Dispatchers.IO) {
                generationService.generate(playerFilesDir, outputDir)
            }

            // Store the generated package in the uploads storage
            val storedPath = withContext(Dispatchers.IO) {
                uploadsService.saveFileBytes(generatedFile.fileName.toString(), generatedFile.toFile().readBytes())
            }

            return roomRepository.save(room.copy(generatedWorldPath = storedPath)).awaitSingle()
        } finally {
            // Clean up temp directories
            withContext(Dispatchers.IO) {
                playerFilesDir.toFile().deleteRecursively()
                outputDir.toFile().deleteRecursively()
            }
        }
    }

    /**
     * Deletes the generated world for the room, unlocking entry editing.
     * Only admins can delete the generated world.
     */
    @Transactional
    suspend fun deleteGeneratedWorld(roomId: Long, userId: Long): Room {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can delete the generated world")
        }

        if (room.generatedWorldPath == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated world exists for this room")
        }

        uploadsService.deleteFile(room.generatedWorldPath)
        return roomRepository.save(room.copy(generatedWorldPath = null)).awaitSingle()
    }

    /**
     * Retrieves room with entries and apworlds; enforces membership or admin access
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
                EntryInfo(entry.id, entry.name, entry.game, discordService.getUserInfo(entry.userId))
            }
        val apworlds = apworldRepository.findByRoomId(roomId)
            .asFlow()
            .toList()
            .map { apworld ->
                if (apworld.id == null) error("Apworld ID is null after saving")
                ApworldInfo(apworld.id, apworld.gameName, discordService.getUserInfo(apworld.userId))
            }
        return RoomWithEntries(room, entries, apworlds, isAdmin)
    }

    suspend fun isAdminOfGuild(guildId: Long, userId: Long): Boolean =
        discordService.isAdminOfGuild(userId, guildId)

    suspend fun getEntry(entryId: Long): Entry? = entryRepository.findById(entryId).awaitSingleOrNull()
}

data class EntryInfo(val id: Long, val name: String, val game: String, val user: UserInfo)
data class ApworldInfo(val id: Long, val gameName: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: List<EntryInfo>,
    val apworlds: List<ApworldInfo>,
    val isAdmin: Boolean
) {
    val isGenerated: Boolean get() = room.generatedWorldPath != null
}
