package com.github.derminator.archipelobby.user

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface UserRepository : ReactiveCrudRepository<User, Long> {
    fun findByDiscordId(discordId: String): Mono<User>
    fun countByRole(role: UserRole): Mono<Long>
    fun findByStatus(status: UserStatus): Flux<User>
}
