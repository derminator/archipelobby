package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.ApWorldFile
import com.github.derminator.archipelobby.data.EntryYaml
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
        model: Model
    ): Mono<String> = mono {
        if (principal == null || principal is AnonymousAuthenticationToken) {
            val preview = roomService.getRoomForPreview(roomId)
            model.addAttribute("preview", preview)
            return@mono "room-preview"
        }
        val userId = principal.asDiscordPrincipal.userId
        loadRoomModel(roomId, userId, model)
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
        model: Model,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
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
                    val gameName = gameCatalogService.extractApWorldGame(apworldBytes)
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
                    roomId = roomId,
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
                loadRoomModel(roomId, userId, model)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
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
        val filename = "${entry.name}.yaml"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
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
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${apWorld.fileName}\"")
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
        principal: Principal
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)

        val entries = roomWithEntries.entries.toList()
        val apWorlds = roomService.getApWorldsForRoom(roomId, userId).toList()

        val zipBytes = withContext(Dispatchers.IO) {
            val byteArrayOutputStream = ByteArrayOutputStream()
            ZipOutputStream(byteArrayOutputStream).use { zipOut ->
                for (entryInfo in entries) {
                    val entry = roomService.getEntry(entryInfo.id) ?: continue
                    if (uploadsService.fileExists(entry.yamlFilePath)) {
                        val fileContent = uploadsService.getFile(entry.yamlFilePath)
                        zipOut.putNextEntry(ZipEntry("Players/${entry.name}.yaml"))
                        zipOut.write(fileContent)
                        zipOut.closeEntry()
                    }
                }
                for (apWorldInfo in apWorlds) {
                    val apWorld = roomService.getApWorld(apWorldInfo.id) ?: continue
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
            roomService.uploadGame(roomId, userId, fileBytes, filename)
            "redirect:/rooms/$roomId"
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.BAD_REQUEST
                || e.statusCode == HttpStatus.CONFLICT
                || e.statusCode == HttpStatus.UNPROCESSABLE_ENTITY
            ) {
                loadRoomModel(roomId, userId, model)
                model.addAttribute("errorMessage", e.reason ?: "An error occurred")
                "room"
            } else throw e
        }
    }

    @PostMapping("/{roomId}/generated-game/delete")
    fun deleteGeneratedGame(
        @PathVariable roomId: Long,
        principal: Principal,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
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
        val filename = "${room.name}.archipelago"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(fileContent)
    }

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteRoom(roomId, userId)
        "redirect:/"
    }

    private suspend fun loadRoomsModel(userId: Long, model: Model) {
        model.addAttribute("userRooms", roomService.getRoomsForUser(userId))
        model.addAttribute("adminGuilds", roomService.getAdminGuilds(userId).toList())
        model.addAttribute("joinableRooms", roomService.getJoinableRooms(userId))
    }

    private suspend fun loadRoomModel(roomId: Long, userId: Long, model: Model) {
        val roomWithEntries = roomService.getRoom(roomId, userId)
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries.toList())
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("userId", userId)
        model.addAttribute("apWorlds", roomService.getApWorldsForRoom(roomId, userId).toList())
        model.addAttribute("roomGames", roomWithEntries.roomGames)
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
