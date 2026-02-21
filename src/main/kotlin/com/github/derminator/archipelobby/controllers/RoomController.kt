package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.security.DiscordPrincipal
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Controller
@RequestMapping("/rooms", "/rooms/")
class RoomController(
    private val roomService: RoomService,
    private val uploadsService: UploadsService
) {
    @GetMapping
    fun getRooms(
        principal: DiscordPrincipal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.userId
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
        principal: DiscordPrincipal
    ): Mono<String> = mono {
        val formData = exchange.formData.awaitSingle()
        val guildId = formData.getFirst("guildId")?.toLongOrNull() ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Required form parameter 'guildId' is not present"
        )

        val name = formData.getFirst("name")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")

        val userId = principal.userId

        val room = roomService.createRoom(guildId, name, userId)
        "redirect:/rooms/${room.id}"
    }

    @GetMapping("/{roomId}", "/{roomId}/")
    fun getRoom(
        @PathVariable roomId: Long,
        principal: DiscordPrincipal,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries)
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("userId", userId)
        "room"
    }

    data class AddEntryForm(
        val entryName: String,
        val yamlFile: FilePart,
    )

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        principal: DiscordPrincipal,
        @ModelAttribute form: AddEntryForm,
    ): Mono<String> = mono {
        val userId = principal.userId
        val entryName = form.entryName.trim()
        val yamlFile = form.yamlFile

        if (!yamlFile.filename().endsWith(".yaml") && !yamlFile.filename().endsWith(".yml")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a YAML file")
        }

        val filePath = uploadsService.saveFile(yamlFile)

        roomService.addEntry(roomId, userId, entryName, filePath)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/entries/{entryId}/delete")
    fun deleteEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        principal: DiscordPrincipal
    ): Mono<String> = mono {
        val userId = principal.userId
        val isAdmin = roomService.isAdminOfGuild(
            roomService.getRoom(roomId, userId).room.guildId,
            userId
        )
        roomService.deleteEntry(entryId, userId, isAdmin)
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/entries/{entryId}/rename")
    fun renameEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        exchange: ServerWebExchange,
        principal: DiscordPrincipal
    ): Mono<String> = mono {
        val userId = principal.userId
        val formData = exchange.formData.awaitSingle()
        val newName = formData.getFirst("newName")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'newName' is not present")
        roomService.renameEntry(entryId, userId, newName)
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
        val filename = "${entry.name}.yaml"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
            .body(fileContent)
    }

    @GetMapping("/{roomId}/download-all")
    fun downloadAllYamls(
        @PathVariable roomId: Long,
        principal: DiscordPrincipal
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.userId
        val roomWithEntries = roomService.getRoom(roomId, userId)

        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipOutputStream(byteArrayOutputStream).use { zipOut ->
            for (entryInfo in roomWithEntries.entries) {
                val entry = roomService.getEntry(entryInfo.id) ?: continue
                val fileExists = uploadsService.fileExists(entry.yamlFilePath)
                if (fileExists) {
                    val fileContent = uploadsService.getFile(entry.yamlFilePath)
                    val zipEntry = ZipEntry("${entry.name}.yaml")
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

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: Long,
        principal: DiscordPrincipal
    ): Mono<String> = mono {
        val userId = principal.userId
        roomService.deleteRoom(roomId, userId)
        "redirect:/"
    }
}
