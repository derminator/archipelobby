package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
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
    ): Mono<String> = mono {
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
        roomService.leaveRoom(roomId, userId).awaitSingle()
        "redirect:/"
    }

    @PostMapping("/{roomId}/delete")
    fun deleteRoom(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        roomService.deleteRoom(roomId, userId).awaitSingle()
        "redirect:/"
    }
}
