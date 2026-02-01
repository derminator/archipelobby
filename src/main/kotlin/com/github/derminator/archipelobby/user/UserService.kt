package com.github.derminator.archipelobby.user

import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class UserService(private val userRepository: UserRepository) {

    fun findOrCreateUser(discordId: String, username: String): Mono<User> {
        return userRepository.findByDiscordId(discordId)
            .flatMap { user ->
                if (user.username != username) {
                    userRepository.save(user.copy(username = username))
                } else {
                    Mono.just(user)
                }
            }
            .switchIfEmpty(
                userRepository.save(
                    User(
                        discordId = discordId,
                        username = username,
                        status = UserStatus.PENDING,
                        role = UserRole.USER
                    )
                )
            )
    }

    fun hasAnyAdmin(): Mono<Boolean> {
        return userRepository.countByRole(UserRole.ADMIN).map { it > 0 }
    }

    fun setFirstAdmin(discordId: String, username: String): Mono<User> {
        return hasAnyAdmin().flatMap { hasAdmin ->
            if (hasAdmin) {
                Mono.error(IllegalStateException("Admin already exists"))
            } else {
                userRepository.findByDiscordId(discordId)
                    .flatMap { user ->
                        userRepository.save(user.copy(role = UserRole.ADMIN, status = UserStatus.APPROVED))
                    }
                    .switchIfEmpty(
                        userRepository.save(
                            User(
                                discordId = discordId,
                                username = username,
                                role = UserRole.ADMIN,
                                status = UserStatus.APPROVED
                            )
                        )
                    )
            }
        }
    }

    fun getPendingRequests(): Flux<User> = userRepository.findByStatus(UserStatus.PENDING)

    fun approveUser(discordId: String, role: UserRole): Mono<User> {
        return userRepository.findByDiscordId(discordId)
            .flatMap { user ->
                userRepository.save(user.copy(status = UserStatus.APPROVED, role = role))
            }
    }

    fun denyUser(discordId: String): Mono<User> {
        return userRepository.findByDiscordId(discordId)
            .flatMap { user ->
                userRepository.save(user.copy(status = UserStatus.DENIED))
            }
    }
}
