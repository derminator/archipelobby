package com.github.derminator.archipelobby.admin

import com.github.derminator.archipelobby.user.User
import com.github.derminator.archipelobby.user.UserRole
import com.github.derminator.archipelobby.user.UserService
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/admin")
class AdminController(private val userService: UserService) {

    @GetMapping("/requests")
    fun getPendingRequests(): Flux<User> = userService.getPendingRequests()

    @PostMapping("/approve")
    fun approveUser(@RequestParam discordId: String, @RequestParam role: UserRole): Mono<User> {
        return userService.approveUser(discordId, role)
    }

    @PostMapping("/deny")
    fun denyUser(@RequestParam discordId: String): Mono<User> {
        return userService.denyUser(discordId)
    }
}
