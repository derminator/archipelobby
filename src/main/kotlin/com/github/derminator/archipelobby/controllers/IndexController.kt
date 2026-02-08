package com.github.derminator.archipelobby.controllers

import com.github.derminator.archipelobby.data.RoomService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import reactor.core.publisher.Mono

@Controller
class IndexController(private val roomService: RoomService) {

    @GetMapping("/")
    fun index(
        @AuthenticationPrincipal principal: OAuth2User?,
        model: Model
    ): Mono<String> {
        if (principal == null) {
            return Mono.just("index")
        }

        val userId = principal.name.toLongOrNull() ?: return Mono.just("index")
        return Mono.zip(
            roomService.getRoomsForUser(userId).collectList(),
            roomService.getAdminGuilds(userId).collectList(),
            roomService.getJoinableRooms(userId).collectList()
        ).map { tuple ->
            model.addAttribute("userRooms", tuple.t1)
            model.addAttribute("adminGuilds", tuple.t2)
            model.addAttribute("joinableRooms", tuple.t3)
            "index"
        }
    }
}
