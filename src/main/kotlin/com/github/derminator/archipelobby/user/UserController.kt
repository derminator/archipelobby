package com.github.derminator.archipelobby.user

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/user")
class UserController(private val userService: UserService) {

    @GetMapping("/status")
    fun getStatus(@AuthenticationPrincipal principal: Any?): Mono<UserStatusResponse> {
        if (principal is OAuth2User) {
            val discordId = principal.attributes["id"]?.toString() ?: return Mono.just(UserStatusResponse("UNKNOWN", "UNKNOWN"))
            return userService.findOrCreateUser(discordId, principal.name).map { user ->
                UserStatusResponse(user.status.name, user.role.name)
            }
        }
        return Mono.just(UserStatusResponse("ANONYMOUS", "NONE"))
    }
}

data class UserStatusResponse(val status: String, val role: String)
