package com.github.derminator.archipelobby.data

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Table("ROOMS")
data class Room(
    @Id val id: Long? = null,
    val guildId: Long,
    val name: String,
    val generatedGameFilePath: String? = null,
    @Version val version: Long = 0,
)

@Table("ENTRIES")
data class Entry(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long,
    val name: String,
    val game: String,
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

@Table("APWORLDS")
data class ApWorld(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long,
    val fileName: String,
    val filePath: String
)

interface ApWorldRepository : ReactiveCrudRepository<ApWorld, Long> {
    fun findByRoomId(roomId: Long): Flux<ApWorld>
    fun existsByRoomIdAndFileName(roomId: Long, fileName: String): Mono<Boolean>
}
