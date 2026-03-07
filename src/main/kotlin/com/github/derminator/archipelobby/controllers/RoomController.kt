package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.game.GameService
import com.github.derminator.archipelobby.security.asDiscordPrincipal
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.security.Principal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.yaml.snakeyaml.Yaml

@Controller
@RequestMapping("/rooms", "/rooms/")
class RoomController(
    private val roomService: RoomService,
    private val uploadsService: UploadsService,
    private val gameService: GameService
) {
    @GetMapping
    fun getRooms(
        principal: Principal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val userRooms = roomService.getRoomsForUser(userId)
        val adminGuilds = roomService.getAdminGuilds(userId).toList()
        val joinableRooms = roomService.getJoinableRooms(userId)

        model.addAttribute("userRooms", userRooms)
        model.addAttribute("adminGuilds", adminGuilds)
        model.addAttribute("joinableRooms", joinableRooms)
        "rooms"
    }

    @PostMapping
    fun createRoom(
        exchange: ServerWebExchange,
        principal: Principal
    ): Mono<String> = mono {
        val formData = exchange.formData.awaitSingle()
        val guildId = formData.getFirst("guildId")?.toLongOrNull() ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Required form parameter 'guildId' is not present"
        )

        val name = formData.getFirst("name")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")

        val userId = principal.asDiscordPrincipal.userId

        val room = roomService.createRoom(guildId, name, userId)
        "redirect:/rooms/${room.id}"
    }

    @GetMapping("/{roomId}", "/{roomId}/")
    fun getRoom(
        @PathVariable roomId: Long,
        principal: Principal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries)
        model.addAttribute("apworlds", roomWithEntries.apworlds)
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("isGenerated", roomWithEntries.isGenerated)
        model.addAttribute("userId", userId)
        "room"
    }

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        principal: Principal,
        exchange: ServerWebExchange,
    ): Mono<String> = mono {
        val multipart = exchange.multipartData.awaitSingle()

        val yamlFilePart = multipart["yamlFile"]?.firstOrNull() as? FilePart
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "YAML file is required")

        if (!yamlFilePart.filename().endsWith(".yaml") && !yamlFilePart.filename().endsWith(".yml")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a YAML file")
        }

        val yamlBytes = readBytes(yamlFilePart)

        val games = try {
            gameService.parseGamesFromYaml(yamlBytes)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }

        val entryName = extractNameFromYaml(yamlBytes)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "YAML file must contain a 'name' field")

        // For entries with multiple games, record all as comma-separated
        val game = games.joinToString(", ")

        val userId = principal.asDiscordPrincipal.userId
        val filePath = uploadsService.saveFileBytes(yamlFilePart.filename(), yamlBytes)
        try {
            roomService.addEntry(roomId, userId, entryName, game, filePath)
        } catch (e: Exception) {
            uploadsService.deleteFile(filePath)
            throw e
        }
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/apworlds")
    fun addApworld(
        @PathVariable roomId: Long,
        principal: Principal,
        exchange: ServerWebExchange,
    ): Mono<String> = mono {
        val multipart = exchange.multipartData.awaitSingle()

        val apworldFilePart = multipart["apworldFile"]?.firstOrNull() as? FilePart
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "apworld file is required")

        if (!apworldFilePart.filename().endsWith(".apworld")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apworld file")
        }

        val apworldBytes = readBytes(apworldFilePart)

        val gameName = try {
            gameService.extractGameNameFromApworld(apworldBytes)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }

        val userId = principal.asDiscordPrincipal.userId
        val filePath = uploadsService.saveFileBytes(apworldFilePart.filename(), apworldBytes)
        roomService.addApworld(roomId, userId, gameName, filePath)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/entries/{entryId}/delete")
    fun deleteEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val isAdmin = roomService.isAdminOfGuild(
            roomService.getRoom(roomId, userId).room.guildId,
            userId
        )
        roomService.deleteEntry(entryId, userId, isAdmin)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/entries/{entryId}/download")
    fun downloadEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val entry = roomService.getEntry(entryId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        if (entry.roomId != roomId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Entry does not belong to this room")
        }

        val fileExists = uploadsService.fileExists(entry.yamlFilePath)
        if (!fileExists) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val fileContent = uploadsService.getFile(entry.yamlFilePath)
        val filename = "${extractNameFromYaml(fileContent) ?: "entry"}.yaml"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
            .body(fileContent)
    }

    @GetMapping("/{roomId}/download-all")
    fun downloadAllYamls(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)

        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipOutputStream(byteArrayOutputStream).use { zipOut ->
            for (entryInfo in roomWithEntries.entries) {
                val entry = roomService.getEntry(entryInfo.id) ?: continue
                val fileExists = uploadsService.fileExists(entry.yamlFilePath)
                if (fileExists) {
                    val fileContent = uploadsService.getFile(entry.yamlFilePath)
                    val zipEntry = ZipEntry("${extractNameFromYaml(fileContent) ?: "entry"}.yaml")
                    zipOut.putNextEntry(zipEntry)
                    zipOut.write(fileContent)
                    zipOut.closeEntry()
                }
            }
        }

        val zipBytes = byteArrayOutputStream.toByteArray()
        val filename = "${roomWithEntries.room.name}_yamls.zip"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(zipBytes)
    }

    @PostMapping("/{roomId}/generate")
    fun generateGame(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.generateWorld(roomId, userId)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/delete-generated-world")
    fun deleteGeneratedWorld(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        roomService.deleteGeneratedWorld(roomId, userId)
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/download-world")
    fun downloadWorld(
        @PathVariable roomId: Long,
        principal: Principal
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)
        if (!roomWithEntries.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can download the generated world")
        }
        val path = roomWithEntries.room.generatedWorldPath
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated world exists for this room")
        val bytes = uploadsService.getFile(path)
        val filename = "${roomWithEntries.room.name}.archipelago"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
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

    private fun extractNameFromYaml(content: ByteArray): String? {
        return try {
            val yaml = Yaml()
            val data = yaml.load<Any>(content.inputStream())
            if (data is Map<*, *>) {
                data["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readBytes(filePart: FilePart): ByteArray {
        val output = ByteArrayOutputStream()
        filePart.content().collectList().awaitSingle().forEach { dataBuffer ->
            val bytes = ByteArray(dataBuffer.readableByteCount())
            dataBuffer.read(bytes)
            output.write(bytes)
        }
        return output.toByteArray()
    }
}
