package com.github.derminator.archipelobby

import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Controller
@RequestMapping("/rooms")
class RoomController(private val roomService: RoomService) {

    @PostMapping
    fun createRoom(
        @RequestParam guildId: Long,
        @RequestParam name: String,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> {
        val userId =
            principal.name.toLongOrNull() ?: return Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return roomService.createRoom(guildId, name, userId)
            .map { "redirect:/rooms/${it.id}" }
    }

    @GetMapping("/{roomId}")
    fun getRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User,
        model: Model
    ): Mono<String> {
        val userId =
            principal.name.toLongOrNull() ?: return Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return roomService.getRoom(roomId, userId)
            .map { roomWithMembers ->
                model.addAttribute("room", roomWithMembers.room)
                model.addAttribute("members", roomWithMembers.members)
                model.addAttribute("isAdmin", roomWithMembers.isAdmin)
                model.addAttribute("isMember", roomWithMembers.isMember)
                "room"
            }
    }

    @PostMapping("/{roomId}/join")
    fun joinRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> {
        val userId =
            principal.name.toLongOrNull() ?: return Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return roomService.joinRoom(roomId, userId)
            .map { "redirect:/rooms/$roomId" }
    }

    @PostMapping("/{roomId}/leave")
    fun leaveRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> {
        val userId =
            principal.name.toLongOrNull() ?: return Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return roomService.leaveRoom(roomId, userId)
            .thenReturn("redirect:/")
    }

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> {
        val userId =
            principal.name.toLongOrNull() ?: return Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return roomService.deleteRoom(roomId, userId)
            .thenReturn("redirect:/")
    }
}
