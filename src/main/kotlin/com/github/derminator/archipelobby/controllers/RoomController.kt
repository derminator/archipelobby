package com.github.derminator.archipelobby.controllers

import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import com.github.derminator.archipelobby.data.ApWorldFile
import com.github.derminator.archipelobby.data.EntryYaml
import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.security.asDiscordPrincipal
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBufferUtils
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

@Controller
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
    private val uploadsService: UploadsService
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

    @GetMapping("/{roomId}")
    fun getRoom(
        @PathVariable roomId: Long,
        principal: Principal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)
        val apWorlds = roomService.getApWorldsForRoom(roomId, userId)
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries)
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("userId", userId)
        model.addAttribute("apWorlds", apWorlds)
        "room"
    }

    data class AddEntryForm(
        val yamlFile: FilePart,
        val apworldFile: FilePart?,
    )

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        principal: Principal,
        @ModelAttribute form: AddEntryForm,
    ): Mono<String> = mono {
        val userId = principal.asDiscordPrincipal.userId
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

        val filePath = uploadsService.saveFile(fileBytes, yamlFile.filename())

        val apworldFilePart = form.apworldFile
        val apWorldFile: ApWorldFile? = if (apworldFilePart != null && apworldFilePart.filename().isNotEmpty()) {
            if (!apworldFilePart.filename().endsWith(".apworld")) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "APWorld file must have .apworld extension")
            }
            val apworldBytes = readFilePart(apworldFilePart)
            ApWorldFile(apworldFilePart.filename(), uploadsService.saveFile(apworldBytes, apworldFilePart.filename()))
        } else null

        val savedPaths = listOfNotNull(filePath, apWorldFile?.filePath)

        try {
            roomService.addEntry(roomId, userId, entryYaml.name, entryYaml.game, filePath, apWorldFile)
        } catch (e: Exception) {
            savedPaths.forEach { runCatching { uploadsService.deleteFile(it) } }
            throw e
        }

        "redirect:/rooms/$roomId"
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
        val apWorlds = roomService.getApWorldsForRoom(roomId, userId)

        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipOutputStream(byteArrayOutputStream).use { zipOut ->
            for (entryInfo in roomWithEntries.entries) {
                val entry = roomService.getEntry(entryInfo.id) ?: continue
                val fileExists = uploadsService.fileExists(entry.yamlFilePath)
                if (fileExists) {
                    val fileContent = uploadsService.getFile(entry.yamlFilePath)
                    val zipEntry = ZipEntry("Players/${entry.name}.yaml")
                    zipOut.putNextEntry(zipEntry)
                    zipOut.write(fileContent)
                    zipOut.closeEntry()
                }
            }
            for (apWorldInfo in apWorlds) {
                val apWorld = roomService.getApWorld(apWorldInfo.id) ?: continue
                val fileExists = uploadsService.fileExists(apWorld.filePath)
                if (fileExists) {
                    val fileContent = uploadsService.getFile(apWorld.filePath)
                    val zipEntry = ZipEntry("custom_worlds/${apWorld.fileName}")
                    zipOut.putNextEntry(zipEntry)
                    zipOut.write(fileContent)
                    zipOut.closeEntry()
                }
            }
        }

        val zipBytes = byteArrayOutputStream.toByteArray()
        val filename = "${roomWithEntries.room.name}.zip"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(zipBytes)
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
