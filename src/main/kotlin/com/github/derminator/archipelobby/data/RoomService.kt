package com.github.derminator.archipelobby.data

import com.github.derminator.archipelobby.discord.DiscordService
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import com.github.derminator.archipelobby.extractFilesFromZip
import com.github.derminator.archipelobby.matchPatchesToEntries
import com.github.derminator.archipelobby.generator.ArchipelagoGeneratorService
import com.github.derminator.archipelobby.generator.GameCatalogService
import com.github.derminator.archipelobby.generator.GameInfo
import com.github.derminator.archipelobby.generator.GenerationJobLimiter
import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.SaveDataService
import com.github.derminator.archipelobby.storage.UploadsService
import org.slf4j.LoggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val entryRepository: EntryRepository,
    private val entryPatchFileRepository: EntryPatchFileRepository,
    private val apWorldRepository: ApWorldRepository,
    private val discordService: DiscordService,
    private val uploadsService: UploadsService,
    private val archipelagoGeneratorService: ArchipelagoGeneratorService,
    private val gameCatalogService: GameCatalogService,
    private val multiServerManager: MultiServerManager,
    private val saveDataService: SaveDataService,
    private val generationJobLimiter: GenerationJobLimiter = GenerationJobLimiter(),
    @Value($$"${archipelobby.resources.max-generation-input-bytes:268435456}")
    private val maxGenerationInputBytes: Long = 256L * 1024 * 1024,
) {
    private val logger = LoggerFactory.getLogger(RoomService::class.java)

    init {
        require(maxGenerationInputBytes > 0)
    }

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

        val supportedGames = buildRoomGames(roomId).map { it.name }.toMutableSet()
        apWorldFile?.gameName?.takeIf { it.isNotBlank() }?.let { supportedGames.add(it) }
        if (game !in supportedGames) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Game '$game' is not supported in this room. " +
                    "Upload an apworld that provides it, or pick a game from the supported list.",
            )
        }

        val locationCount = generationJobLimiter.run {
            val existingApWorlds = apWorldRepository.findByRoomId(roomId).asFlow().toList()
            val inputPaths = buildList {
                add(GenerationInputPath("yaml", yamlFilePath, isYaml = true))
                existingApWorlds.forEach { add(GenerationInputPath(it.fileName, it.filePath, isYaml = false)) }
                apWorldFile?.let { add(GenerationInputPath(it.fileName, it.filePath, isYaml = false)) }
            }
            val (yamlFiles, apWorldContents) = loadGenerationInputs(inputPaths)
            archipelagoGeneratorService.getLocationCount(yamlFiles.getValue("yaml"), apWorldContents)
        }

        val entry = entryRepository.save(
            Entry(
                roomId = roomId,
                userId = userId,
                name = entryName,
                game = game,
                yamlFilePath = yamlFilePath,
                locationCount = locationCount,
            ),
        ).awaitSingle()

        if (apWorldFile != null) {
            if (apWorldRepository.existsByRoomIdAndFileName(roomId, apWorldFile.fileName).awaitSingle()) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An APWorld with filename '${apWorldFile.fileName}' already exists in this room",
                )
            }
            val conflicting = apWorldRepository
                .findByRoomIdAndGameName(roomId, apWorldFile.gameName)
                .awaitSingleOrNull()
            if (conflicting != null) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An APWorld for game '${apWorldFile.gameName}' is already uploaded to " +
                        "this room (${conflicting.fileName}). Remove it before uploading a replacement.",
                )
            }
            try {
                apWorldRepository.save(
                    ApWorld(
                        roomId = roomId,
                        userId = userId,
                        fileName = apWorldFile.fileName,
                        filePath = apWorldFile.filePath,
                        gameName = apWorldFile.gameName,
                    ),
                ).awaitSingle()
            } catch (e: DataIntegrityViolationException) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An APWorld for game '${apWorldFile.gameName}' is already uploaded to this room.",
                    e,
                )
            }
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

    // Callers stop the room's server (RoomController) before invoking this, so the
    // server's up-to-10s shutdown wait stays out of this transaction.
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
                        val patches = entryPatchFileRepository.findByEntryId(entry.id).asFlow().toList()
                            .mapNotNull { patch -> patch.id?.let { PatchFileInfo(it, patch.fileName) } }
                        send(
                            EntryInfo(
                                entry.id,
                                entry.name,
                                entry.game,
                                discordService.getUserInfo(entry.userId),
                                entry.locationCount,
                                patches,
                            ),
                        )
                    }
                }
        }
        val roomGames = buildRoomGames(roomId)
        return RoomWithEntries(room, entries, isAdmin, roomGames)
    }

    private suspend fun buildRoomGames(roomId: Long): List<GameInfo> {
        val coreGames = gameCatalogService.listCoreGames()
        val apWorlds = apWorldRepository.findByRoomId(roomId).asFlow().toList()
        val apWorldByGame = apWorlds
            .filter { it.gameName.isNotBlank() }
            .associateBy { it.gameName }
        // Archipelago's loader uses the apworld's class at generation time when
        // a game is provided by both a core world and an uploaded apworld, so
        // swap the core entry in place when an apworld shadows it.
        val merged = coreGames.map { core ->
            apWorldByGame[core.name]
                ?.let { GameInfo(core.name, apworldFileName = it.fileName) }
                ?: core
        }.toMutableList()
        val coreNames = coreGames.map { it.name }.toSet()
        for (apWorld in apWorlds) {
            if (apWorld.gameName.isBlank()) continue
            if (apWorld.gameName !in coreNames) {
                merged.add(GameInfo(apWorld.gameName, apworldFileName = apWorld.fileName))
            }
        }
        return merged.sortedBy { it.name.lowercase() }
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

    suspend fun getPatchForDownload(patchId: Long, roomId: Long, userId: Long): EntryPatchFile {
        val patch = entryPatchFileRepository.findById(patchId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patch file not found")
        val entry = entryRepository.findById(patch.entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patch file not found")
        if (entry.roomId != roomId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Patch does not belong to this room")
        }
        val room = roomRepository.findById(roomId).awaitSingle()
        if (!discordService.isMemberOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to room")
        }
        return patch
    }

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

    suspend fun generateGame(roomId: Long, userId: Long) = generationJobLimiter.run {
        val (room, entries) = validateAndFetchRoomDetailsForGeneration(roomId, userId)

        // Lock before loading inputs so duplicate requests cannot preload the same
        // room and all memory-heavy work remains inside the global job permit.
        val lockedRoom = try {
            roomRepository.save(room.copy(generatedGameFilePath = Room.GENERATING_SENTINEL)).awaitSingle()
        } catch (error: Throwable) {
            resetGenerationSentinel(roomId)
            if (error is OptimisticLockingFailureException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
            }
            throw error
        }

        val generatedGame = try {
            val apWorlds = apWorldRepository.findByRoomId(roomId).asFlow().toList()
            val inputPaths = buildList {
                entries.forEach { add(GenerationInputPath(it.name, it.yamlFilePath, isYaml = true)) }
                apWorlds.forEach { add(GenerationInputPath(it.fileName, it.filePath, isYaml = false)) }
            }
            val (yamlFiles, apWorldFiles) = loadGenerationInputs(inputPaths)
            archipelagoGeneratorService.generate(yamlFiles, apWorldFiles)
        } catch (e: Throwable) {
            resetGenerationSentinel(roomId)
            throw e
        }

        val createdPaths = mutableListOf<String>()
        val createdPatchPaths = mutableListOf<String>()
        val savedRoom = try {
            val gameFilePath = uploadsService.saveFile(
                generatedGame.archipelagoBytes,
                "${room.name}.archipelago",
            ).also(createdPaths::add)
            val walkthroughFilePath = uploadsService.saveFile(
                generatedGame.walkthroughBytes,
                "${room.name}_Spoiler.txt",
            ).also(createdPaths::add)
            val persistedRoom = roomRepository.save(
                lockedRoom.copy(generatedGameFilePath = gameFilePath, walkthroughFilePath = walkthroughFilePath)
            ).awaitSingle()
            persistPatchFiles(entries, generatedGame.patchFiles, createdPaths, createdPatchPaths)
            persistedRoom
        } catch (error: Throwable) {
            val roomRepaired = resetGenerationSentinel(roomId, *createdPaths.toTypedArray())
            if (roomRepaired) {
                val deletedPatchPaths = deletePatchRecordsBestEffort(*createdPatchPaths.toTypedArray())
                val retainedPatchPaths = createdPatchPaths.toSet() - deletedPatchPaths
                deleteFilesBestEffort(*createdPaths.filterNot(retainedPatchPaths::contains).toTypedArray())
            } else {
                logger.error("Retaining generated artifacts because room {} could not be repaired", roomId)
            }
            if (error is OptimisticLockingFailureException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
            }
            throw error
        }

        val savedRoomId = savedRoom.id ?: error("Room ID is null after save")
        try {
            multiServerManager.startServer(savedRoomId)
        } catch (e: Exception) {
            logger.error("Failed to auto-start server for room {} after generation", savedRoomId, e)
        }
    }

    private suspend fun resetGenerationSentinel(roomId: Long, vararg generatedPaths: String): Boolean =
        withContext(NonCancellable) {
            try {
                val current = roomRepository.findById(roomId).awaitSingleOrNull()
                if (current != null && (
                        current.generatedGameFilePath == Room.GENERATING_SENTINEL ||
                            current.generatedGameFilePath in generatedPaths ||
                            current.walkthroughFilePath in generatedPaths
                        )
                ) {
                    roomRepository.save(
                        current.copy(generatedGameFilePath = null, walkthroughFilePath = null),
                    ).awaitSingle()
                }
                true
            } catch (error: Throwable) {
                logger.error("Failed to clear generation state for room {}", roomId, error)
                false
            }
        }

    private suspend fun deleteFilesBestEffort(vararg paths: String) {
        withContext(NonCancellable) {
            paths.forEach { path ->
                runCatching { uploadsService.deleteFile(path) }
                    .onFailure { error -> logger.error("Failed to delete generated file {}", path, error) }
            }
        }
    }

    private suspend fun deletePatchRecordsBestEffort(vararg paths: String): Set<String> =
        withContext(NonCancellable) {
            val deleted = mutableSetOf<String>()
            paths.forEach { path ->
                runCatching { entryPatchFileRepository.deleteByFilePath(path).awaitSingleOrNull() }
                    .onSuccess { deleted += path }
                    .onFailure { error -> logger.error("Failed to delete patch metadata for {}", path, error) }
            }
            deleted
        }

    private suspend fun loadGenerationInputs(
        inputs: List<GenerationInputPath>,
    ): Pair<Map<String, ByteArray>, Map<String, ByteArray>> {
        val yamlFiles = LinkedHashMap<String, ByteArray>()
        val apWorldFiles = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (input in inputs) {
            val remaining = maxGenerationInputBytes - total
            if (remaining <= 0) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Generation input exceeds $maxGenerationInputBytes bytes",
                )
            }
            val bytes = uploadsService.getFile(input.path, remaining)
            total += bytes.size
            if (input.isYaml) yamlFiles[input.name] = bytes else apWorldFiles[input.name] = bytes
        }
        return yamlFiles to apWorldFiles
    }

    private data class GenerationInputPath(val name: String, val path: String, val isYaml: Boolean)

    /**
     * Saves each patch file extracted from a generated/uploaded game and records it against the
     * owning slot. Failures propagate: it is better to surface an error than to silently lose a
     * patch file.
     */
    private suspend fun persistPatchFiles(
        entries: List<Entry>,
        patchFiles: Map<String, ByteArray>,
        createdPaths: MutableList<String>,
        createdPatchPaths: MutableList<String>,
    ) {
        if (patchFiles.isEmpty()) return
        val byEntryId = matchPatchesToEntries(entries, patchFiles)
        for ((entryId, patches) in byEntryId) {
            for (patch in patches) {
                val path = uploadsService.saveFile(patch.bytes, patch.fileName).also(createdPaths::add)
                createdPatchPaths += path
                entryPatchFileRepository.save(
                    EntryPatchFile(entryId = entryId, fileName = patch.fileName, filePath = path),
                ).awaitSingle()
            }
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
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot generate a game with no entries")
        }
        return Pair(room, entries)
    }

    @Transactional
    suspend fun uploadGame(roomId: Long, userId: Long, gameBytes: ByteArray, filename: String) {
        val (room, entries) = validateAndFetchRoomDetailsForGeneration(roomId, userId)

        val archipelagoBytes: ByteArray?
        val walkthroughBytes: ByteArray?
        val patchFiles: Map<String, ByteArray>
        when {
            filename.endsWith(".archipelago") -> {
                archipelagoBytes = gameBytes
                walkthroughBytes = null
                patchFiles = emptyMap()
            }
            filename.endsWith(".zip") -> {
                val extracted = extractFilesFromZip(gameBytes)
                if (extracted.archipelagoBytes == null) throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ZIP file does not contain a .archipelago file",
                )
                archipelagoBytes = extracted.archipelagoBytes
                walkthroughBytes = extracted.walkthroughBytes
                patchFiles = extracted.patchFiles
            }
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "File must be a .archipelago file or a .zip containing one",
            )
        }

        val createdPaths = mutableListOf<String>()
        val createdPatchPaths = mutableListOf<String>()
        val gameFilePath: String
        val walkthroughFilePath: String?
        val savedRoom = try {
            gameFilePath = uploadsService.saveFile(archipelagoBytes, "${room.name}.archipelago")
                .also(createdPaths::add)
            walkthroughFilePath = walkthroughBytes?.let {
                uploadsService.saveFile(it, "${room.name}_Spoiler.txt").also(createdPaths::add)
            }
            val persistedRoom = roomRepository.save(
                room.copy(generatedGameFilePath = gameFilePath, walkthroughFilePath = walkthroughFilePath)
            ).awaitSingle()
            persistPatchFiles(entries, patchFiles, createdPaths, createdPatchPaths)
            persistedRoom
        } catch (error: Throwable) {
            val roomRepaired = resetGenerationSentinel(roomId, *createdPaths.toTypedArray())
            if (roomRepaired) {
                val deletedPatchPaths = deletePatchRecordsBestEffort(*createdPatchPaths.toTypedArray())
                val retainedPatchPaths = createdPatchPaths.toSet() - deletedPatchPaths
                deleteFilesBestEffort(*createdPaths.filterNot(retainedPatchPaths::contains).toTypedArray())
            } else {
                logger.error("Retaining uploaded artifacts because room {} could not be repaired", roomId)
            }
            if (error is OptimisticLockingFailureException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Room was modified concurrently, please try again")
            }
            throw error
        }

        val savedRoomId = savedRoom.id ?: error("Room ID is null after save")
        try {
            multiServerManager.startServer(savedRoomId)
        } catch (e: Exception) {
            logger.error("Failed to auto-start server for room {} after upload", savedRoomId, e)
        }
    }

    // Callers stop the room's server (RoomController) before invoking this, so the
    // server is confirmed dead before the save state is cleared (a late autosave
    // can't resurrect it) and the shutdown wait stays out of this transaction.
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

        roomRepository.save(
            room.copy(generatedGameFilePath = null, walkthroughFilePath = null)
        ).awaitSingle()
        saveDataService.clear(roomId)
        runCatching { uploadsService.deleteFile(filePath) }
        room.walkthroughFilePath?.let { runCatching { uploadsService.deleteFile(it) } }

        // Remove the per-slot patch files and their records too, so submissions reopen cleanly.
        val entries = entryRepository.findByRoomId(roomId).asFlow().toList()
        for (entry in entries) {
            val entryId = entry.id ?: continue
            entryPatchFileRepository.findByEntryId(entryId).asFlow().collect { patch ->
                runCatching { uploadsService.deleteFile(patch.filePath) }
                patch.id?.let { entryPatchFileRepository.deleteById(it).awaitSingleOrNull() }
            }
        }
    }

    suspend fun getGeneratedGameBytes(roomId: Long): ByteArray? {
        val room = roomRepository.findById(roomId).awaitSingleOrNull() ?: return null
        val path = room.generatedGameFilePath?.takeUnless { it == Room.GENERATING_SENTINEL } ?: return null
        return if (uploadsService.fileExists(path)) uploadsService.getFile(path) else null
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

    suspend fun startServer(roomId: Long, userId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        if (!room.isGenerated) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "No generated game to start a server for")
        }
        multiServerManager.startServer(roomId)
    }

    suspend fun stopServer(roomId: Long, userId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")
        if (!discordService.isAdminOfGuild(userId, room.guildId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this guild")
        }
        multiServerManager.stopServer(roomId)
    }

    fun isServerRunning(roomId: Long): Boolean = multiServerManager.isRunning(roomId)

    suspend fun getRoomForPreview(roomId: Long): RoomPreview {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val entries = entryRepository.findByRoomId(roomId).asFlow().toList()
        val games = entries.map { it.game }.distinct().sorted()
        return RoomPreview(room.name, entries.size, games)
    }
}

data class RoomPreview(val name: String, val entryCount: Int, val games: List<String>)
data class ApWorldFile(val fileName: String, val filePath: String, val gameName: String)
data class EntryInfo(
    val id: Long,
    val name: String,
    val game: String,
    val user: UserInfo,
    val locationCount: Int,
    val patches: List<PatchFileInfo> = emptyList(),
)
data class PatchFileInfo(val id: Long, val fileName: String)
data class ApWorldInfo(val id: Long, val fileName: String, val user: UserInfo)
data class RoomWithEntries(
    val room: Room,
    val entries: Flow<EntryInfo>,
    val isAdmin: Boolean,
    val roomGames: List<GameInfo>,
)
