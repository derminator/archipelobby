package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.DownloadZipWriter
import com.github.derminator.archipelobby.data.ApWorldFile
import com.github.derminator.archipelobby.safeDownloadFileName
import com.github.derminator.archipelobby.data.EntryYaml
import com.github.derminator.archipelobby.data.Puns
import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.generator.GameCatalogService
import com.github.derminator.archipelobby.generator.GenerationJobLimiter
import com.github.derminator.archipelobby.security.asDiscordPrincipal
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import java.security.Principal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

@Controller
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
    private val uploadsService: UploadsService,
    private val gameCatalogService: GameCatalogService,
    private val generationJobLimiter: GenerationJobLimiter,
    @Value($$"${archipelobby.resources.max-upload-bytes:67108864}")
    private val maxUploadBytes: Int = 64 * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-download-uncompressed-bytes:134217728}")
    private val maxDownloadUncompressedBytes: Long = 128L * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-download-archive-bytes:67108864}")
    private val maxDownloadArchiveBytes: Long = 64L * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-download-entries:256}")
    private val maxDownloadEntries: Int = 256,
    @Value($$"${archipelobby.resources.max-concurrent-downloads:2}")
    maxConcurrentDownloads: Int = 2,
) {
    private val logger = LoggerFactory.getLogger(RoomController::class.java)
    private val downloadPermits = Semaphore(maxConcurrentDownloads.also { require(it > 0) })
    private val yamlMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    init {
        require(maxUploadBytes > 0)
        require(maxDownloadUncompressedBytes > 0)
        require(maxDownloadArchiveBytes > 0)
        require(maxDownloadEntries > 0)
        require(maxDownloadEntries < Int.MAX_VALUE)
    }

    @GetMapping
    fun getRooms(
        principal: Principal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        loadRoomsModel(userId, model)
        "rooms"
    }

    @PostMapping
    fun createRoom(
        exchange: ServerWebExchange,
        principal: Principal,
        model: Model,
    ): Mono<String> = mono {
        val formData = exchange.formData.awaitSingle()
        val userId = principal.asDiscordPrincipal.userId
        try {
            val guildId = formData.getFirst("guildId")?.toLongOrNull() ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Required form parameter 'guildId' is not present"
            )

            val name = formData.getFirst("name")
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")

            val room = roomService.createRoom(guildId, name, userId)
            "redirect:/rooms/${room.id}"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST || e.statusCode == HttpStatus.CONFLICT) {
                loadRoomsModel(userId, model)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "rooms"
            } else throw e
        }
    }

    @GetMapping("/{roomId}")
    fun getRoom(
        @PathVariable roomId: Long,
        principal: Principal?,
        exchange: ServerWebExchange,
        model: Model
    ): Mono<String> = mono {
        if (principal == null || principal is AnonymousAuthenticationToken) {
            val preview = roomService.getRoomForPreview(roomId)
            model.addAttribute("preview", preview)
            model.addAttribute("pun", Puns.forRoom(roomId))
            return@mono "room-preview"
        }
        val userId = principal.asDiscordPrincipal.userId
        loadRoomModel(roomId, userId, model, exchange)
        "room"
    }

    data class AddEntryForm(
        val yamlFile: FilePart,
        val apworldFile: FilePart?,
    )

    data class UploadGameForm(val gameFile: FilePart)

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        principal: Principal,
        @ModelAttribute form: AddEntryForm,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        generationJobLimiter.run {
            try {
                val yamlFile = form.yamlFile

            if (!yamlFile.filename().endsWith(".yaml") && !yamlFile.filename().endsWith(".yml")) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a YAML file")
            }

            val fileBytes = readFilePart(yamlFile)
            val entryYaml = try {
                yamlMapper.readValue(fileBytes, EntryYaml::class.java)
            } catch (e: tools.jackson.core.JacksonException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid YAML file: ${e.originalMessage}")
            }

            // Validate the apworld (if any) before saving anything to disk, so a bad
            // apworld never leaves an orphaned YAML file behind.
            val apworldFilePart = form.apworldFile
            val pendingApWorld: Triple<String, ByteArray, String>? =
                if (apworldFilePart != null && apworldFilePart.filename().isNotEmpty()) {
                    val apworldBytes = readFilePart(apworldFilePart)
                    val gameName = gameCatalogService.extractApWorldGame(apworldBytes, apworldFilePart.filename())
                    Triple(apworldFilePart.filename(), apworldBytes, gameName)
                } else null

            val savedPaths = mutableListOf<String>()
            try {
                val filePath = uploadsService.saveFile(fileBytes, yamlFile.filename())
                    .also(savedPaths::add)
                val apWorldFile: ApWorldFile? = pendingApWorld?.let { (name, bytes, gameName) ->
                    ApWorldFile(
                        fileName = name,
                        filePath = uploadsService.saveFile(bytes, name).also(savedPaths::add),
                        gameName = gameName,
                    )
                }
                roomService.addEntry(
                    roomId = roomId,
                    userId = userId,
                    entryName = entryYaml.name,
                    game = entryYaml.game,
                    yamlFilePath = filePath,
                    apWorldFile = apWorldFile,
                )
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    savedPaths.forEach { runCatching { uploadsService.deleteFile(it) } }
                }
                throw e
            }

            "redirect:/rooms/$roomId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST || e.statusCode == HttpStatus.CONFLICT) {
                loadRoomModel(roomId, userId, model, exchange)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
        }
    }
    }

    @PostMapping("/{roomId}/entries/{entryId}/delete")
    fun deleteEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteEntry(entryId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/entries/{entryId}/download")
    fun downloadEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val entry = roomService.getEntryForDownload(entryId, roomId, userId)

        val fileExists = uploadsService.fileExists(entry.yamlFilePath)
        if (!fileExists) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val fileContent = uploadsService.getFile(entry.yamlFilePath)
        val filename = safeDownloadFileName(entry.name, ".yaml")

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
            .body(fileContent)
    }

    @GetMapping("/{roomId}/patches/{patchId}/download")
    fun downloadPatch(
        @PathVariable roomId: Long,
        @PathVariable patchId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val patch = roomService.getPatchForDownload(patchId, roomId, userId)

        if (!uploadsService.fileExists(patch.filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patch file not found")
        }

        val fileContent = uploadsService.getFile(patch.filePath)

        ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"${safeDownloadFileName(patch.fileName)}\"",
            )
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @GetMapping("/{roomId}/apworlds/{apworldId}/download")
    fun downloadApWorld(
        @PathVariable roomId: Long,
        @PathVariable apworldId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val apWorld = roomService.getApWorldForDownload(apworldId, roomId, userId)

        val fileExists = uploadsService.fileExists(apWorld.filePath)
        if (!fileExists) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val fileContent = uploadsService.getFile(apWorld.filePath)

        ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"${safeDownloadFileName(apWorld.fileName, ".apworld")}\"",
            )
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @PostMapping("/{roomId}/apworlds/{apworldId}/delete")
    fun deleteApWorld(
        @PathVariable roomId: Long,
        @PathVariable apworldId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteApWorld(apworldId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/download")
    fun downloadAll(
        @PathVariable roomId: Long,
        principal: Principal,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)
        val body = Flux.usingWhen(
            mono { createDownloadArchive(roomId, userId, roomWithEntries) },
            { archive ->
                DataBufferUtils.readByteChannel(
                    { archive.channel },
                    exchange.response.bufferFactory(),
                    64 * 1024,
                )
            },
            DownloadArchiveResource::cleanup,
            { archive, _ -> archive.cleanup() },
            DownloadArchiveResource::cleanup,
        )
        val filename = safeDownloadFileName(roomWithEntries.room.name, ".zip")
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(body)
    }

    private suspend fun createDownloadArchive(
        roomId: Long,
        userId: Long,
        roomWithEntries: com.github.derminator.archipelobby.data.RoomWithEntries,
    ): DownloadArchiveResource {
        if (!downloadPermits.tryAcquire()) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many downloads are already running")
        }
        var zipPath: java.nio.file.Path? = null
        var readChannel: FileChannel? = null
        var archiveReturned = false
        try {
            val entries = roomWithEntries.entries.take(maxDownloadEntries + 1).toList()
            if (entries.size > maxDownloadEntries) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Download ZIP entry count exceeds $maxDownloadEntries",
                )
            }
            val remainingEntries = maxDownloadEntries - entries.size
            val apWorlds = roomService.getApWorldsForRoom(roomId, userId).take(remainingEntries + 1).toList()
            if (apWorlds.size > remainingEntries) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Download ZIP entry count exceeds $maxDownloadEntries",
                )
            }

            zipPath = Files.createTempFile("archipelobby-download-", ".zip")
            withContext(Dispatchers.IO) {
                Files.newOutputStream(
                    zipPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    DownloadZipWriter(
                        output,
                        maxUncompressedBytes = maxDownloadUncompressedBytes,
                        maxArchiveBytes = maxDownloadArchiveBytes,
                        maxEntries = maxDownloadEntries,
                    ).use { writer ->
                        for ((id) in entries) {
                            val entry = roomService.getEntry(id) ?: continue
                            if (uploadsService.fileExists(entry.yamlFilePath)) {
                                val safeName = safeDownloadFileName(entry.name, ".yaml")
                                writer.add("Players/${id}_$safeName", uploadsService.getFile(entry.yamlFilePath))
                            }
                        }
                        for ((id) in apWorlds) {
                            val apWorld = roomService.getApWorld(id) ?: continue
                            if (uploadsService.fileExists(apWorld.filePath)) {
                                val safeName = safeDownloadFileName(apWorld.fileName, ".apworld")
                                writer.add("custom_worlds/${id}_$safeName", uploadsService.getFile(apWorld.filePath))
                            }
                        }
                    }
                }
            }
            val openedChannel = FileChannel.open(zipPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            readChannel = openedChannel
            return DownloadArchiveResource(zipPath, openedChannel, downloadPermits).also { archiveReturned = true }
        } finally {
            if (!archiveReturned) {
                runCatching { readChannel?.close() }
                val deleted = zipPath?.let(::deleteTemporaryArchiveWithRetries) ?: true
                if (deleted) {
                    downloadPermits.release()
                } else {
                    logger.error("Unable to delete failed download archive {}; retaining its permit", zipPath)
                }
            }
        }
    }

    @PostMapping("/{roomId}/generate")
    fun generateGame(
        @PathVariable roomId: Long,
        principal: Principal,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.generateGame(roomId, userId)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/upload-game")
    fun uploadGame(
        @PathVariable roomId: Long,
        principal: Principal,
        @ModelAttribute form: UploadGameForm,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        generationJobLimiter.run {
            try {
                val filePart = form.gameFile
            val filename = filePart.filename()
            if (!filename.endsWith(".archipelago") && !filename.endsWith(".zip")) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File must be a .archipelago file or a .zip containing one"
                )
            }
            val fileBytes = readFilePart(filePart)
            roomService.uploadGame(roomId, userId, fileBytes, filename)
            "redirect:/rooms/$roomId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST
                || e.statusCode == HttpStatus.CONFLICT
                || e.statusCode == HttpStatus.UNPROCESSABLE_CONTENT
            ) {
                loadRoomModel(roomId, userId, model, exchange)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
        }
    }
    }

    @PostMapping("/{roomId}/generated-game/delete")
    fun deleteGeneratedGame(
        @PathVariable roomId: Long,
        principal: Principal,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        // Stop the server first (outside the delete's transaction) so its up-to-10s
        // shutdown wait doesn't hold the DB connection, and a late autosave can't
        // resurrect the save state deleteGeneratedGame is about to clear.
        roomService.stopServer(roomId, userId)
        roomService.deleteGeneratedGame(roomId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/generated-game/download")
    fun downloadGeneratedGame(
        @PathVariable roomId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val room = roomService.getGeneratedGameForDownload(roomId, userId)
        val filePath = room.generatedGameFilePath

        if (filePath == null || !uploadsService.fileExists(filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Generated game file not found")
        }

        val fileContent = uploadsService.getFile(filePath)
        val filename = safeDownloadFileName(room.name, ".archipelago")

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @GetMapping("/{roomId}/walkthrough/download")
    fun downloadWalkthrough(
        @PathVariable roomId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val room = roomService.getWalkthroughForDownload(roomId, userId)
        val filePath = room.walkthroughFilePath

        if (filePath == null || !uploadsService.fileExists(filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Walkthrough file not found")
        }

        val fileContent = uploadsService.getFile(filePath)

        ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"${safeDownloadFileName("${room.name}_Spoiler", ".txt")}\"",
            )
            .contentType(MediaType.TEXT_PLAIN)
            .body(fileContent)
    }

    @PostMapping("/{roomId}/server/start")
    fun startServer(
        @PathVariable roomId: Long,
        principal: Principal,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        handleRoomAction(roomId, userId, exchange, model) {
            roomService.startServer(roomId, userId)
        }
    }

    @PostMapping("/{roomId}/server/stop")
    fun stopServer(
        @PathVariable roomId: Long,
        principal: Principal,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        handleRoomAction(roomId, userId, exchange, model) {
            roomService.stopServer(roomId, userId)
        }
    }

    private suspend fun handleRoomAction(
        roomId: Long,
        userId: Long,
        exchange: ServerWebExchange,
        model: Model,
        action: suspend () -> Unit,
    ): String = try {
        action()
        "redirect:/rooms/$roomId"
    } catch (e: ResponseStatusException) {
        if (e.statusCode != HttpStatus.BAD_REQUEST && e.statusCode != HttpStatus.CONFLICT) {
            throw e
        }

        loadRoomModel(roomId, userId, model, exchange)
        model.addAttribute("errorMessage", e.reason ?: "An error occurred")
        "room"
    }

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        // Stop the server first (outside the delete's transaction) so its shutdown
        // wait doesn't hold the DB connection open.
        roomService.stopServer(roomId, userId)
        roomService.deleteRoom(roomId, userId)
        "redirect:/"
    }

    private suspend fun loadRoomsModel(userId: Long, model: Model) {
        model.addAttribute("userRooms", roomService.getRoomsForUser(userId))
        model.addAttribute("adminGuilds", roomService.getAdminGuilds(userId).toList())
        model.addAttribute("joinableRooms", roomService.getJoinableRooms(userId))
    }

    private suspend fun loadRoomModel(roomId: Long, userId: Long, model: Model, exchange: ServerWebExchange) {
        val roomWithEntries = roomService.getRoom(roomId, userId)
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries.toList())
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("userId", userId)
        model.addAttribute("apWorlds", roomService.getApWorldsForRoom(roomId, userId).toList())
        model.addAttribute("roomGames", roomWithEntries.roomGames)
        model.addAttribute("pun", Puns.forRoom(roomId))
        // Full WebSocket connect address when the server is running, otherwise null.
        // The UI derives the running/stopped state from whether this is present.
        val serverAddress = if (roomService.isServerRunning(roomId)) {
            val uri = exchange.request.uri
            val host = uri.host + if (uri.port > 0) ":${uri.port}" else ""
            val scheme = if (uri.scheme == "https") "wss" else "ws"
            "$scheme://$host/rooms/$roomId/ws"
        } else {
            null
        }
        model.addAttribute("serverAddress", serverAddress)
    }

    private suspend fun readFilePart(filePart: FilePart): ByteArray {
        val dataBuffer = try {
            DataBufferUtils.join(filePart.content(), maxUploadBytes).awaitSingle()
        } catch (error: DataBufferLimitException) {
            throw ResponseStatusException(
                HttpStatus.CONTENT_TOO_LARGE,
                "Upload exceeds the $maxUploadBytes byte limit",
                error,
            )
        }
        return try {
            ByteArray(dataBuffer.readableByteCount()).also(dataBuffer::read)
        } finally {
            DataBufferUtils.release(dataBuffer)
        }
    }
}

internal class DownloadArchiveResource(
    val path: Path,
    val channel: FileChannel,
    private val permits: Semaphore,
) {
    private val cleaned = AtomicBoolean()
    private val cleanupLock = Any()

    fun cleanup(): Mono<Void> = Mono.fromRunnable<Void> {
        synchronized<Unit>(cleanupLock) {
            if (!cleaned.get()) {
                runCatching { channel.close() }
                val deleted = deleteTemporaryArchiveWithRetries(path)
                if (!deleted) {
                    throw IllegalStateException("Unable to delete temporary download archive $path")
                }
                cleaned.set(true)
                permits.release()
            }
        }
    }.subscribeOn(Schedulers.boundedElastic()).then()
}

private fun deleteTemporaryArchiveWithRetries(path: Path): Boolean {
    repeat(3) { attempt ->
        try {
            Files.deleteIfExists(path)
            return true
        } catch (_: Exception) {
            if (attempt < 2) Thread.sleep(25L * (attempt + 1))
        }
    }
    return false
}
