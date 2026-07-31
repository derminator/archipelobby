package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.ApWorldFile
import com.github.derminator.archipelobby.data.EntryYaml
import com.github.derminator.archipelobby.data.Puns
import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.generator.GameCatalogService
import com.github.derminator.archipelobby.security.asDiscordPrincipal
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.springframework.core.io.buffer.DataBufferUtils
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
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.ByteArrayOutputStream
import java.security.Principal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Controller
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
    private val uploadsService: UploadsService,
    private val gameCatalogService: GameCatalogService,
) {
    private val yamlMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

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
            "redirect:/rooms/${room.urlId}"
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
        @PathVariable roomId: String,
        principal: Principal?,
        exchange: ServerWebExchange,
        model: Model
    ): Mono<String> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        if (principal == null || principal is AnonymousAuthenticationToken) {
            val preview = roomService.getRoomForPreview(internalRoomId)
            model.addAttribute("preview", preview)
            model.addAttribute("pun", Puns.forRoom(internalRoomId))
            return@mono "room-preview"
        }
        val userId = principal.asDiscordPrincipal.userId
        loadRoomModel(internalRoomId, userId, model, exchange, roomId)
        "room"
    }

    data class AddEntryForm(
        val yamlFile: FilePart,
        val apworldFile: FilePart?,
    )

    data class UploadGameForm(val gameFile: FilePart)

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: String,
        principal: Principal,
        @ModelAttribute form: AddEntryForm,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
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

            val filePath = uploadsService.saveFile(fileBytes, yamlFile.filename())
            val apWorldFile: ApWorldFile? = pendingApWorld?.let { (name, bytes, gameName) ->
                ApWorldFile(
                    fileName = name,
                    filePath = uploadsService.saveFile(bytes, name),
                    gameName = gameName,
                )
            }

            val savedPaths = listOfNotNull(filePath, apWorldFile?.filePath)

            try {
                roomService.addEntry(
                    roomId = internalRoomId,
                    userId = userId,
                    entryName = entryYaml.name,
                    game = entryYaml.game,
                    yamlFilePath = filePath,
                    apWorldFile = apWorldFile,
                )
            } catch (e: Exception) {
                savedPaths.forEach { runCatching { uploadsService.deleteFile(it) } }
                throw e
            }

            "redirect:/rooms/$roomId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST || e.statusCode == HttpStatus.CONFLICT) {
                loadRoomModel(internalRoomId, userId, model, exchange, roomId)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
        }
    }

    @PostMapping("/{roomId}/entries/{entryId}/delete")
    fun deleteEntry(
        @PathVariable roomId: String,
        @PathVariable entryId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteEntry(entryId, internalRoomId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/entries/{entryId}/download")
    fun downloadEntry(
        @PathVariable roomId: String,
        @PathVariable entryId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        val entry = roomService.getEntryForDownload(entryId, internalRoomId, userId)

        val fileExists = uploadsService.fileExists(entry.yamlFilePath)
        if (!fileExists) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val fileContent = uploadsService.getFile(entry.yamlFilePath)
        val filename = "${entry.name}.yaml"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
            .body(fileContent)
    }

    @GetMapping("/{roomId}/patches/{patchId}/download")
    fun downloadPatch(
        @PathVariable roomId: String,
        @PathVariable patchId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        val patch = roomService.getPatchForDownload(patchId, internalRoomId, userId)

        if (!uploadsService.fileExists(patch.filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Patch file not found")
        }

        val fileContent = uploadsService.getFile(patch.filePath)

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${patch.fileName}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @GetMapping("/{roomId}/apworlds/{apworldId}/download")
    fun downloadApWorld(
        @PathVariable roomId: String,
        @PathVariable apworldId: Long,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        val apWorld = roomService.getApWorldForDownload(apworldId, internalRoomId, userId)

        val fileExists = uploadsService.fileExists(apWorld.filePath)
        if (!fileExists) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val fileContent = uploadsService.getFile(apWorld.filePath)

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${apWorld.fileName}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @PostMapping("/{roomId}/apworlds/{apworldId}/delete")
    fun deleteApWorld(
        @PathVariable roomId: String,
        @PathVariable apworldId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteApWorld(apworldId, internalRoomId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/download")
    fun downloadAll(
        @PathVariable roomId: String,
        principal: Principal
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(internalRoomId, userId)

        val entries = roomWithEntries.entries.toList()
        val apWorlds = roomService.getApWorldsForRoom(internalRoomId, userId).toList()

        val zipBytes = withContext(Dispatchers.IO) {
            val byteArrayOutputStream = ByteArrayOutputStream()
            ZipOutputStream(byteArrayOutputStream).use { zipOut ->
                for ((id) in entries) {
                    val entry = roomService.getEntry(id) ?: continue
                    if (uploadsService.fileExists(entry.yamlFilePath)) {
                        val fileContent = uploadsService.getFile(entry.yamlFilePath)
                        zipOut.putNextEntry(ZipEntry("Players/${entry.name}.yaml"))
                        zipOut.write(fileContent)
                        zipOut.closeEntry()
                    }
                }
                for ((id) in apWorlds) {
                    val apWorld = roomService.getApWorld(id) ?: continue
                    if (uploadsService.fileExists(apWorld.filePath)) {
                        val fileContent = uploadsService.getFile(apWorld.filePath)
                        zipOut.putNextEntry(ZipEntry("custom_worlds/${apWorld.fileName}"))
                        zipOut.write(fileContent)
                        zipOut.closeEntry()
                    }
                }
            }
            byteArrayOutputStream.toByteArray()
        }
        val filename = "${roomWithEntries.room.name}.zip"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(zipBytes)
    }

    @PostMapping("/{roomId}/generate")
    fun generateGame(
        @PathVariable roomId: String,
        principal: Principal,
    ): Mono<String> = mono {
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        val userId = principal.asDiscordPrincipal.userId
        roomService.generateGame(internalRoomId, userId)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/upload-game")
    fun uploadGame(
        @PathVariable roomId: String,
        principal: Principal,
        @ModelAttribute form: UploadGameForm,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
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
            roomService.uploadGame(roomService.resolveRoomUrlId(roomId), userId, fileBytes, filename)
            "redirect:/rooms/$roomId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST
                || e.statusCode == HttpStatus.CONFLICT
                || e.statusCode == HttpStatus.UNPROCESSABLE_CONTENT
            ) {
                loadRoomModel(roomService.resolveRoomUrlId(roomId), userId, model, exchange, roomId)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
        }
    }

    @PostMapping("/{roomId}/generated-game/delete")
    fun deleteGeneratedGame(
        @PathVariable roomId: String,
        principal: Principal,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        // Stop the server first (outside the delete's transaction) so its up-to-10s
        // shutdown wait doesn't hold the DB connection, and a late autosave can't
        // resurrect the save state deleteGeneratedGame is about to clear.
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        roomService.stopServer(internalRoomId, userId)
        roomService.deleteGeneratedGame(internalRoomId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/generated-game/download")
    fun downloadGeneratedGame(
        @PathVariable roomId: String,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val room = roomService.getGeneratedGameForDownload(roomService.resolveRoomUrlId(roomId), userId)
        val filePath = room.generatedGameFilePath

        if (filePath == null || !uploadsService.fileExists(filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Generated game file not found")
        }

        val fileContent = uploadsService.getFile(filePath)
        val filename = "${room.name}.archipelago"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @GetMapping("/{roomId}/walkthrough/download")
    fun downloadWalkthrough(
        @PathVariable roomId: String,
        principal: Principal,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val room = roomService.getWalkthroughForDownload(roomService.resolveRoomUrlId(roomId), userId)
        val filePath = room.walkthroughFilePath

        if (filePath == null || !uploadsService.fileExists(filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Walkthrough file not found")
        }

        val fileContent = uploadsService.getFile(filePath)

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${room.name}_Spoiler.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(fileContent)
    }

    @PostMapping("/{roomId}/server/start")
    fun startServer(
        @PathVariable roomId: String,
        principal: Principal,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        handleRoomAction(roomId, userId, exchange, model) { internalRoomId ->
            roomService.startServer(internalRoomId, userId)
        }
    }

    @PostMapping("/{roomId}/server/stop")
    fun stopServer(
        @PathVariable roomId: String,
        principal: Principal,
        exchange: ServerWebExchange,
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        handleRoomAction(roomId, userId, exchange, model) { internalRoomId ->
            roomService.stopServer(internalRoomId, userId)
        }
    }

    private suspend fun handleRoomAction(
        roomUrlId: String,
        userId: Long,
        exchange: ServerWebExchange,
        model: Model,
        action: suspend (Long) -> Unit,
    ): String {
        val internalRoomId = roomService.resolveRoomUrlId(roomUrlId)
        return try {
            action(internalRoomId)
            "redirect:/rooms/$roomUrlId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode != HttpStatus.BAD_REQUEST && e.statusCode != HttpStatus.CONFLICT) {
                throw e
            }

            loadRoomModel(internalRoomId, userId, model, exchange, roomUrlId)
            model.addAttribute("errorMessage", e.reason ?: "An error occurred")
            "room"
        }
    }

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: String,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        // Stop the server first (outside the delete's transaction) so its shutdown
        // wait doesn't hold the DB connection open.
        val internalRoomId = roomService.resolveRoomUrlId(roomId)
        roomService.stopServer(internalRoomId, userId)
        roomService.deleteRoom(internalRoomId, userId)
        "redirect:/"
    }

    private suspend fun loadRoomsModel(userId: Long, model: Model) {
        model.addAttribute("userRooms", roomService.getRoomsForUser(userId))
        model.addAttribute("adminGuilds", roomService.getAdminGuilds(userId).toList())
        model.addAttribute("joinableRooms", roomService.getJoinableRooms(userId))
    }

    private suspend fun loadRoomModel(
        roomId: Long,
        userId: Long,
        model: Model,
        exchange: ServerWebExchange,
        roomUrlId: String = roomId.toString(),
    ) {
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
            "$scheme://$host/rooms/$roomUrlId/ws"
        } else {
            null
        }
        model.addAttribute("serverAddress", serverAddress)
    }

    private suspend fun readFilePart(filePart: FilePart): ByteArray {
        val outputStream = ByteArrayOutputStream()
        filePart.content().collectList().awaitSingle().forEach { dataBuffer ->
            val bytes = ByteArray(dataBuffer.readableByteCount())
            dataBuffer.read(bytes)
            DataBufferUtils.release(dataBuffer)
            outputStream.write(bytes)
        }
        return outputStream.toByteArray()
    }
}
