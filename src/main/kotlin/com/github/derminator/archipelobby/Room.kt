package com.github.derminator.archipelobby

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Table("rooms")
data class Room(
    @Id val id: Long? = null,
    val guildId: Long,
    val name: String
) {
    fun addMember(userId: Long): RoomMember {
        if (id == null) {
            error("Room must be saved before adding members")
        }
        return RoomMember(roomId = id, userId = userId)
    }
}

@Table("room_members")
data class RoomMember(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long
)

interface RoomRepository : ReactiveCrudRepository<Room, Long> {
    fun findByGuildId(guildId: Long): Flux<Room>
}

interface RoomMemberRepository : ReactiveCrudRepository<RoomMember, Long> {
    fun findByRoomId(roomId: Long): Flux<RoomMember>
    fun findByRoomIdAndUserId(roomId: Long, userId: Long): Mono<RoomMember>
    fun findByUserId(userId: Long): Flux<RoomMember>
    fun deleteByRoomIdAndUserId(roomId: Long, userId: Long): Mono<Void>
    fun deleteByRoomId(roomId: Long): Mono<Void>
}
