package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Controller
@RequestMapping("/rooms")
class RoomController(private val roomService: RoomService, private val storageService: StorageService) {
    @GetMapping
    fun getRooms(): String {
        return "redirect:/"
    }

    @PostMapping
    fun createRoom(
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val formData = exchange.formData.awaitSingle()
        val guildId = formData.getFirst("guildId")?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'guildId' is not present")
        val name = formData.getFirst("name")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")

        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val room = roomService.createRoom(guildId, name, userId).awaitSingle()
        "redirect:/rooms/${room.id}"
    }

    @GetMapping("/{roomId}")
    fun getRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User,
        model: Model
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val roomWithMembers = roomService.getRoom(roomId, userId).awaitSingle()
        model.addAttribute("room", roomWithMembers.room)
        model.addAttribute("members", roomWithMembers.members)
        model.addAttribute("isAdmin", roomWithMembers.isAdmin)
        model.addAttribute("isMember", roomWithMembers.isMember)
        model.addAttribute("yamlFiles", storageService.listRoomYamls(roomId))
        "room"
    }

    @PostMapping("/{roomId}/join")
    fun joinRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        roomService.joinRoom(roomId, userId).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{roomId}/leave")
    fun leaveRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        roomService.leaveRoom(roomId, userId).awaitSingleOrNull()
        "redirect:/"
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

    @PostMapping("/{roomId}/yaml", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadYaml(
        @PathVariable roomId: Long,
        @RequestPart("file") file: org.springframework.http.codec.multipart.FilePart,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val room = roomService.getRoom(roomId, userId).awaitSingle()
        if (!room.isMember) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this room")
        val dataBuffer = DataBufferUtils.join(file.content()).awaitSingle()
        dataBuffer.asInputStream(true).use { input ->
            storageService.storeRoomYaml(roomId, file.filename(), input)
        }
        "redirect:/rooms/$roomId"
    }

    @GetMapping("/{roomId}/yamls/{filename}")
    fun downloadYaml(
        @PathVariable roomId: Long,
        @PathVariable filename: String,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<ResponseEntity<FileSystemResource>> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val room = roomService.getRoom(roomId, userId).awaitSingle()
        if (!room.isMember && !room.isAdmin) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val resource = storageService.loadRoomYaml(roomId, filename)
        val headers = HttpHeaders()
        headers.contentDisposition = ContentDisposition.attachment().filename(resource.filename ?: filename).build()
        headers.contentType = MediaType.APPLICATION_OCTET_STREAM
        ResponseEntity.ok().headers(headers).body(resource)
    }
}
