package com.github.derminator.archipelobby

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Table("ROOMS")
data class Room(
    @Id val id: Long? = null,
    val guildId: Long,
    val name: String
)

@Table("ENTRIES")
data class Entry(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long,
    val name: String
)

interface RoomRepository : ReactiveCrudRepository<Room, Long> {
    fun findByGuildId(guildId: Long): Flux<Room>
}

interface EntryRepository : ReactiveCrudRepository<Entry, Long> {
    fun findByRoomId(roomId: Long): Flux<Entry>
    fun findByUserId(userId: Long): Flux<Entry>
    fun countByRoomIdAndUserId(roomId: Long, userId: Long): Mono<Long>
}
