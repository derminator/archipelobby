package com.github.derminator.archipelobby.data

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Table("ROOMS")
data class Room(
    @Id val id: Long? = null,
    val guildId: Long,
    val name: String,
    val state: RoomState = RoomState.WAITING_FOR_PLAYERS,
    val serverAddress: String? = null
)

@Table("ENTRIES")
data class Entry(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long,
    val name: String,
    val yamlFilePath: String
)

interface RoomRepository : ReactiveCrudRepository<Room, Long> {
    fun findByGuildId(guildId: Long): Flux<Room>
    fun existsByGuildIdAndName(guildId: Long, name: String): Mono<Boolean>
}

interface EntryRepository : ReactiveCrudRepository<Entry, Long> {
    fun findByRoomId(roomId: Long): Flux<Entry>
    fun findByUserId(userId: Long): Flux<Entry>
    fun countByRoomIdAndUserId(roomId: Long, userId: Long): Mono<Long>
    fun existsByRoomIdAndName(roomId: Long, name: String): Mono<Boolean>
}
