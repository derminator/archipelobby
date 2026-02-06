package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Controller
@RequestMapping("/rooms/{roomId}")
class FileUploadController(
    private val fileUploadService: FileUploadService,
    private val roomService: RoomService
) {

    @PostMapping("/yaml/upload")
    fun uploadYaml(
        @PathVariable roomId: Long,
        @RequestPart("file") filePart: FilePart,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        // Verify user is a member
        val roomWithMembers = roomService.getRoom(roomId, userId).awaitSingle()
        if (!roomWithMembers.isMember) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be a room member to upload")
        }

        fileUploadService.uploadYaml(roomId, userId, filePart).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/yaml/delete")
    fun deleteYaml(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        fileUploadService.deleteYaml(roomId, userId).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/apworld/upload")
    fun uploadApWorld(
        @PathVariable roomId: Long,
        @RequestPart("file") filePart: FilePart,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        // Verify user is admin
        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to upload APWorld files")
        }

        fileUploadService.uploadApWorld(roomId, userId, filePart).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/apworld/{apWorldId}/delete")
    fun deleteApWorld(
        @PathVariable roomId: Long,
        @PathVariable apWorldId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        // Verify user is admin
        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to delete APWorld files")
        }

        fileUploadService.deleteApWorld(apWorldId).awaitSingle()
        "redirect:/rooms/$roomId"
    }
}
