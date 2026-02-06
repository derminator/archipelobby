package com.github.derminator.archipelobby

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime

enum class RoomState {
    WAITING_FOR_PLAYERS,
    GENERATING,
    RUNNING,
    COMPLETED
}

@Table("AP_WORLDS")
data class ApWorld(
    @Id val id: Long? = null,
    val roomId: Long,
    val uploadedBy: Long,
    val worldName: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val uploadedAt: LocalDateTime = LocalDateTime.now()
)

@Table("YAML_UPLOADS")
data class YamlUpload(
    @Id val id: Long? = null,
    val roomId: Long,
    val userId: Long,
    val gameName: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val uploadedAt: LocalDateTime = LocalDateTime.now()
)

@Table("SUPPORTED_GAMES")
data class SupportedGame(
    @Id val id: Long? = null,
    val roomId: Long,
    val gameName: String,
    val isCoreVerified: Boolean = false,
    val setupGuideUrl: String? = null,
    val yamlFormUrl: String? = null,
    val requiresApworld: Boolean = false,
    val apworldId: Long? = null,
    val addedAt: LocalDateTime = LocalDateTime.now()
)

interface ApWorldRepository : ReactiveCrudRepository<ApWorld, Long> {
    fun findByRoomId(roomId: Long): Flux<ApWorld>
    fun findByRoomIdAndWorldName(roomId: Long, worldName: String): Mono<ApWorld>
}

interface YamlUploadRepository : ReactiveCrudRepository<YamlUpload, Long> {
    fun findByRoomId(roomId: Long): Flux<YamlUpload>
    fun findByRoomIdAndUserId(roomId: Long, userId: Long): Mono<YamlUpload>
    fun deleteByRoomIdAndUserId(roomId: Long, userId: Long): Mono<Void>
}

interface SupportedGameRepository : ReactiveCrudRepository<SupportedGame, Long> {
    fun findByRoomId(roomId: Long): Flux<SupportedGame>
    fun findByRoomIdAndGameName(roomId: Long, gameName: String): Mono<SupportedGame>
    fun deleteByRoomIdAndGameName(roomId: Long, gameName: String): Mono<Void>
}
