package com.github.derminator.archipelobby.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

enum class UserStatus {
    PENDING, APPROVED, DENIED
}

enum class UserRole {
    USER, ADMIN
}

@Table("USERS")
data class User(
    @Id val id: Long? = null,
    val discordId: String,
    val username: String,
    val status: UserStatus = UserStatus.PENDING,
    val role: UserRole = UserRole.USER
)
