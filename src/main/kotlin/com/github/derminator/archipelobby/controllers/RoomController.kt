package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
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
        val guildId = formData.getFirst("guildId")?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'guildId' is not present")
        val name = formData.getFirst("name")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required form parameter 'name' is not present")
        val entryName = formData.getFirst("entryName")
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Required form parameter 'entryName' is not present"
            )

        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val room = roomService.createRoom(guildId, name, userId, entryName).awaitSingle()
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
        model.addAttribute("hasMembership", roomWithEntries.hasMembership)
        model.addAttribute("userId", userId)
        "room"
    }

    @PostMapping("/{roomId}/entries")
    fun addEntry(
        @PathVariable roomId: Long,
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId =
            principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val formData = exchange.formData.awaitSingle()
        val entryName = formData.getFirst("entryName")
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Required form parameter 'entryName' is not present"
            )
        roomService.addEntry(roomId, userId, entryName).awaitSingle()
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
