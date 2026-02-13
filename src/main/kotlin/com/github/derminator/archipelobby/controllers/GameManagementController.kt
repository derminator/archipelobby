package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.GameManagementService
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
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Controller
@RequestMapping("/rooms/{roomId}/games")
class GameManagementController(
    private val gameManagementService: GameManagementService,
    private val roomService: RoomService
) {

    @PostMapping("/add")
    fun addGame(
        @PathVariable roomId: Long,
        exchange: ServerWebExchange,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to add games")
        }

        val formData = exchange.formData.awaitSingle()
        val gameName = formData.getFirst("gameName")
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Required field 'gameName' is missing")
        val isCoreVerified = formData.getFirst("isCoreVerified")?.toBoolean() ?: false
        val setupGuideUrl = formData.getFirst("setupGuideUrl")
        val yamlFormUrl = formData.getFirst("yamlFormUrl")
        val requiresApworld = formData.getFirst("requiresApworld")?.toBoolean() ?: false
        val apworldId = formData.getFirst("apworldId")?.toLongOrNull()

        gameManagementService.addGame(
            roomId = roomId,
            gameName = gameName,
            isCoreVerified = isCoreVerified,
            setupGuideUrl = setupGuideUrl,
            yamlFormUrl = yamlFormUrl,
            requiresApworld = requiresApworld,
            apworldId = apworldId
        ).awaitSingle()

        "redirect:/rooms/$roomId"
    }

    @PostMapping("/{gameId}/delete")
    fun deleteGame(
        @PathVariable roomId: Long,
        @PathVariable gameId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to delete games")
        }

        gameManagementService.deleteGame(gameId).awaitSingle()
        "redirect:/rooms/$roomId"
    }

    @PostMapping("/start")
    fun startGame(
        @PathVariable roomId: Long,
        @AuthenticationPrincipal principal: OAuth2User
    ): Mono<String> = mono {
        val userId = principal.name.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val isAdmin = roomService.getRoom(roomId, userId).awaitSingle().isAdmin
        if (!isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Must be admin to start game")
        }

        gameManagementService.startGame(roomId).awaitSingle()
        "redirect:/rooms/$roomId"
    }
}