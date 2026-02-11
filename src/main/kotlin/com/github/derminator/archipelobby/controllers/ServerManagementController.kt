package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.ArchipelagoServerService
import com.github.derminator.archipelobby.data.RoomService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Controller
@RequestMapping("/rooms/{roomId}/server")
class ServerManagementController(
    private val archipelagoServerService: ArchipelagoServerService,
    private val roomService: RoomService
) {

    @PostMapping("/generate")
    fun generateMultiworld(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to generate multiworld")
        }

        archipelagoServerService.generateMultiworld(roomId).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/start")
    fun startServer(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to start server")
        }

        archipelagoServerService.startServer(roomId).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/stop")
    fun stopServer(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to stop server")
        }

        archipelagoServerService.stopServer(roomId).awaitSingle()
        "redirect:/rooms/$roomId"
    }
}