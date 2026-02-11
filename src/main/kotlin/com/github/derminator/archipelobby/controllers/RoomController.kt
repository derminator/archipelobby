package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Controller
@RequestMapping("/rooms", "/rooms/")
class RoomController(private val roomService: RoomService) {
    @GetMapping
    fun getRooms(
        @AuthenticationPrincipal principal: OAuth2User,
        model: Model
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: return@mono "redirect:/"
        val userRooms = roomService.getRoomsForUser(userId).collectList().awaitSingle()
        val adminGuilds = roomService.getAdminGuilds(userId).collectList().awaitSingle()
        val joinableRooms = roomService.getJoinableRooms(userId).collectList().awaitSingle()

        model.addAttribute("userRooms", userRooms)
        model.addAttribute("adminGuilds", adminGuilds)
        model.addAttribute("joinableRooms", joinableRooms)
        "rooms"
    }

    @PostMapping
    fun createRoom(
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val formData = exchange.formData.awaitSingle()
        val guildId = formData.getFirst("guildId")?.toLongOrNull() ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Required form parameter 'guildId' is not present"
        )

        val name = formData.getFirst("name")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")

        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val room = roomService.createRoom(guildId, name, userId).awaitSingle()
        "redirect:/rooms/${room.id}"
    }

    @GetMapping("/{roomId}", "/{roomId}/")
    fun getRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User,
        model: Model
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val roomWithEntries = roomService.getRoom(roomId, userId).awaitSingle()
        model.addAttribute("room", roomWithEntries.room)
        model.addAttribute("entries", roomWithEntries.entries)
        model.addAttribute("isAdmin", roomWithEntries.isAdmin)
        model.addAttribute("userId", userId)
        "room"
    }

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val multipartData = exchange.multipartData.awaitSingle()

        val entryName = multipartData.getFirst("entryName")?.let { part ->
            when (part) {
                is FilePart -> null
                else -> DataBufferUtils.join(part.content()).awaitSingle().toString(StandardCharsets.UTF_8)
            }
        } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'entryName' is not present")

        val yamlFile = multipartData.getFirst("yamlFile") as? FilePart
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required file 'yamlFile' is not present")

        if (!yamlFile.filename().endsWith(".yaml") && !yamlFile.filename().endsWith(".yml")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a YAML file")
        }

        val uploadsDir = Paths.get("uploads")
        Files.createDirectories(uploadsDir)
        val filePath = uploadsDir.resolve("${System.currentTimeMillis()}_${yamlFile.filename()}")
        yamlFile.transferTo(filePath).awaitSingleOrNull()

        roomService.addEntry(roomId, userId, entryName, filePath.toString()).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/entries/{entryId}/delete")
    fun deleteEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val isAdmin = roomService.isAdminOfGuild(
            roomService.getRoom(roomId, userId).awaitSingle().room.guildId,
            userId
        ).awaitSingle()
        roomService.deleteEntry(entryId, userId, isAdmin).awaitSingleOrNull()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/entries/{entryId}/rename")
    fun renameEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val formData = exchange.formData.awaitSingle()
        val newName = formData.getFirst("newName")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'newName' is not present")
        roomService.renameEntry(entryId, userId, newName).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/entries/{entryId}/download")
    fun downloadEntry(
        @PathVariable roomId: Long,
        @PathVariable entryId: Long,
    ): Mono<ResponseEntity<FileSystemResource>> = mono {
        val entry = roomService.getEntry(entryId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found")

        if (entry.roomId != roomId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Entry does not belong to this room")
        }

        val filePath = Paths.get(entry.yamlFilePath)
        if (!Files.exists(filePath)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        }

        val resource = FileSystemResource(filePath)
        val filename = "${entry.name}.yaml"

        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("application/x-yaml"))
            .body(resource)
    }

    @GetMapping("/{roomId}/download-all")
    fun downloadAllYamls(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<ResponseEntity<ByteArray>> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val roomWithEntries = roomService.getRoom(roomId, userId).awaitSingle()

        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipOutputStream(byteArrayOutputStream).use { zipOut ->
            for (entryInfo in roomWithEntries.entries) {
                val entry = roomService.getEntry(entryInfo.id).awaitSingleOrNull() ?: continue
                val filePath = Paths.get(entry.yamlFilePath)
                if (Files.exists(filePath)) {
                    val zipEntry = ZipEntry("${entry.name}.yaml")
                    zipOut.putNextEntry(zipEntry)
                    Files.newInputStream(filePath).use { input ->
                        input.copyTo(zipOut)
                    }
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
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        roomService.deleteRoom(roomId, userId).awaitSingleOrNull()
        "redirect:/"
    }
}
