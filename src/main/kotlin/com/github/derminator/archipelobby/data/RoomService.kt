package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import com.github.derminator.archipelobby.generator.ArchipelagoGeneratorService
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val entryRepository: EntryRepository,
    private val apWorldRepository: ApWorldRepository,
    private val discordService: DiscordService,
    private val uploadsService: UploadsService,
    private val archipelagoGeneratorService: ArchipelagoGeneratorService,
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
    suspend fun addEntry(
        roomId: Long,
        userId: Long,
        entryName: String,
        game: String,
        yamlFilePath: String,
        apWorldFile: ApWorldFile? = null,
    ): Entry {
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!isRoomJoinable(room, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot join this room")
        }

        if (room.generatedGameFilePath != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Game has already been generated for this room")
        }

        if (entryName.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry name cannot be empty")
        }

        val nameExists = entryRepository.existsByRoomIdAndName(roomId, entryName).awaitSingle()
        if (nameExists) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An entry with this name already exists in this room")
        }

        val entry = entryRepository.save(
            Entry(
                roomId = roomId,
                userId = userId,
                name = entryName,
                game = game,
                yamlFilePath = yamlFilePath,
            ),
        ).awaitSingle()

        if (apWorldFile != null) {
            if (apWorldRepository.existsByRoomIdAndFileName(roomId, apWorldFile.fileName).awaitSingle()) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An APWorld with filename '${apWorldFile.fileName}' already exists in this room",
                )
            }
            apWorldRepository.save(
                ApWorld(
                    roomId = roomId,
                    userId = userId,
                    fileName = apWorldFile.fileName,
                    filePath = apWorldFile.filePath,
                ),
            ).awaitSingle()
        }

        return entry
    }

    @Transactional
    suspend fun deleteEntry(entryId: Long, userId: Long) {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")
        val room = roomRepository.findById(entry.roomId).awaitSingle()
        val isAdmin = discordService.isAdminOfGuild(userId, room.guildId)

        if (entry.userId != userId && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's entry")
        }

        if (room.generatedGameFilePath != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete entries after the game has been generated")
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
        val isAdmin = discordService.isAdminOfGuild(userId, room.guildId)
        val entries = channelFlow {
            entryRepository.findByRoomId(roomId)
                .asFlow()
                .collect { entry ->
                    launch {
                        if (entry.id == null) error("Entry ID is null after saving")
                        send(EntryInfo(entry.id, entry.name, entry.game, discordService.getUserInfo(entry.userId)))
                    }
                }
        }
        return RoomWithEntries(room, entries, isAdmin)
    }

    suspend fun getEntry(entryId: Long): Entry? = entryRepository.findById(entryId).awaitSingleOrNull()

    suspend fun getEntryForDownload(entryId: Long, roomId: Long, userId: Long): Entry {
        val entry = entryRepository.findById(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")
        if (entry.roomId != roomId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Entry does not belong to this room")
        }
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!discordService.isMemberOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        return entry
    }


    fun getApWorldsForRoom(roomId: Long, userId: Long): Flow<ApWorldInfo> = channelFlow {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isMemberOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        apWorldRepository.findByRoomId(roomId)
            .asFlow()
            .collect { apWorld ->
                launch {
                    if (apWorld.id == null) error("ApWorld ID is null")
                    send(ApWorldInfo(apWorld.id, apWorld.fileName, discordService.getUserInfo(apWorld.userId)))
                }
            }
    }

    suspend fun getApWorld(apWorldId: Long): ApWorld? = apWorldRepository.findById(apWorldId).awaitSingleOrNull()

    suspend fun getApWorldForDownload(apWorldId: Long, roomId: Long, userId: Long): ApWorld {
        val apWorld = apWorldRepository.findById(apWorldId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "APWorld not found")
        if (apWorld.roomId != roomId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "APWorld does not belong to this room")
        }
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!discordService.isMemberOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        return apWorld
    }

    @Transactional
    suspend fun deleteApWorld(apWorldId: Long, userId: Long) {
        val apWorld = apWorldRepository.findById(apWorldId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "APWorld not found")
        val room = roomRepository.findById(apWorld.roomId).awaitSingle()
        val isAdmin = discordService.isAdminOfGuild(userId, room.guildId)

        if (apWorld.userId != userId && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's APWorld")
        }

        if (room.generatedGameFilePath != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete APWorlds after the game has been generated")
        }

        apWorldRepository.deleteById(apWorldId).awaitSingleOrNull()
    }

    suspend fun generateGame(roomId: Long, userId: Long) {
        val (room, entries) = validateAndFetchRoomDetailsForGeneration(roomId, userId)
        val yamlFiles = entries.associate { it.name to uploadsService.getFile(it.yamlFilePath) }

        val apWorlds = apWorldRepository.findByRoomId(roomId).asFlow().toList()
        val apWorldFiles = apWorlds.associate { it.fileName to uploadsService.getFile(it.filePath) }

        // Lock the room immediately so entry/APWorld changes are blocked during generation.
        val lockedRoom = try {
            roomRepository.save(room.copy(generatedGameFilePath = Room.GENERATING_SENTINEL)).awaitSingle()
        } catch (_: OptimisticLockingFailureException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
        }

        val generatedGame = try {
            archipelagoGeneratorService.generate(yamlFiles, apWorldFiles)
        } catch (e: Exception) {
            runCatching {
                roomRepository.save(lockedRoom.copy(generatedGameFilePath = null, walkthroughFilePath = null))
                    .awaitSingle()
            }
            throw e
        }

        val gameFilePath = uploadsService.saveFile(generatedGame.archipelagoBytes, "${room.name}.archipelago")
        val walkthroughFilePath = uploadsService.saveFile(generatedGame.walkthroughBytes, "${room.name}_Spoiler.txt")
        try {
            roomRepository.save(
                lockedRoom.copy(generatedGameFilePath = gameFilePath, walkthroughFilePath = walkthroughFilePath)
            ).awaitSingle()
        } catch (_: OptimisticLockingFailureException) {
            uploadsService.deleteFile(gameFilePath)
            uploadsService.deleteFile(walkthroughFilePath)
            runCatching {
                roomRepository.save(lockedRoom.copy(generatedGameFilePath = null, walkthroughFilePath = null))
                    .awaitSingle()
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
        }
    }

    private suspend fun validateAndFetchRoomDetailsForGeneration(
        roomId: Long,
        userId: Long
    ): Pair<Room, List<Entry>> {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        if (room.generatedGameFilePath != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Game has already been generated for this room")
        }

        val entries = entryRepository.findByRoomId(roomId).asFlow().toList()
        if (entries.isEmpty()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot generate a game with no entries")
        }
        return Pair(room, entries)
    }

    @Transactional
    suspend fun uploadGame(roomId: Long, userId: Long, gameBytes: ByteArray, filename: String) {
        val (room, _) = validateAndFetchRoomDetailsForGeneration(roomId, userId)

        val (archipelagoBytes, walkthroughBytes) = when {
            filename.endsWith(".archipelago") -> Pair(gameBytes, null)
            filename.endsWith(".zip") -> extractFilesFromZip(gameBytes).also { (archipelago, _) ->
                if (archipelago == null) throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ZIP file does not contain a .archipelago file",
                )
            }
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "File must be a .archipelago file or a .zip containing one",
            )
        }

        val gameFilePath = uploadsService.saveFile(archipelagoBytes!!, "${room.name}.archipelago")
        val walkthroughFilePath = walkthroughBytes?.let {
            uploadsService.saveFile(it, "${room.name}_Spoiler.txt")
        }
        try {
            roomRepository.save(
                room.copy(generatedGameFilePath = gameFilePath, walkthroughFilePath = walkthroughFilePath)
            ).awaitSingle()
        } catch (_: OptimisticLockingFailureException) {
            uploadsService.deleteFile(gameFilePath)
            walkthroughFilePath?.let { uploadsService.deleteFile(it) }
            throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
        }
    }

    /**
     * Extracts the .archipelago multidata and any .txt spoiler file from a zip.
     * Follows the same file-type conventions as the upstream Archipelago WebHostLib.
     */
    private fun extractFilesFromZip(zipBytes: ByteArray): Pair<ByteArray?, ByteArray?> {
        var archipelagoBytes: ByteArray? = null
        var walkthroughBytes: ByteArray? = null
        java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name.endsWith(".archipelago") -> archipelagoBytes = zis.readBytes()
                        entry.name.endsWith(".txt") -> walkthroughBytes = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return Pair(archipelagoBytes, walkthroughBytes)
    }

    @Transactional
    suspend fun deleteGeneratedGame(roomId: Long, userId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        if (room.isGenerating) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Game generation is in progress")
        }
        val filePath = room.generatedGameFilePath
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated game found for this room")

        runCatching { uploadsService.deleteFile(filePath) }
        room.walkthroughFilePath?.let { runCatching { uploadsService.deleteFile(it) } }
        roomRepository.save(room.copy(generatedGameFilePath = null, walkthroughFilePath = null)).awaitSingle()
    }

    suspend fun getGeneratedGameForDownload(roomId: Long, userId: Long): Room {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isMemberOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        if (room.isGenerating) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Game generation is in progress")
        }
        if (room.generatedGameFilePath == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated game found for this room")
        }
        return room
    }

    suspend fun getWalkthroughForDownload(roomId: Long, userId: Long): Room {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        if (room.walkthroughFilePath == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No walkthrough found for this room")
        }
        return room
    }

    suspend fun getRoomForPreview(roomId: Long): RoomPreview {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val entries = entryRepository.findByRoomId(roomId).asFlow().toList()
        val games = entries.map { it.game }.distinct().sorted()
        return RoomPreview(room.name, entries.size, games)
    }
}

data class RoomPreview(val name: String, val entryCount: Int, val games: List<String>)
data class ApWorldFile(val fileName: String, val filePath: String)
data class EntryInfo(val id: Long, val name: String, val game: String, val user: UserInfo)
data class ApWorldInfo(val id: Long, val fileName: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: Flow<EntryInfo>,
    val isAdmin: Boolean,
)
