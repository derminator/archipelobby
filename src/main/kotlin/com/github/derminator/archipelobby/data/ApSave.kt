package com.github.derminator.archipelobby.data

import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Mono

@Table("APSAVES")
data class ApSave(
    @Id val roomId: Long,
    val data: ByteArray,
) {
    // Data classes compare ByteArray by reference; override so two saves with the
    // same room and identical bytes are equal (and hash consistently).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApSave) return false
        return roomId == other.roomId && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * roomId.hashCode() + data.contentHashCode()
}

interface ApSaveRepository : ReactiveCrudRepository<ApSave, Long> {

    @Query("SELECT data FROM APSAVES WHERE room_id = :roomId")
    fun findDataByRoomId(roomId: Long): Mono<ByteArray>

    @Modifying
    @Query("MERGE INTO APSAVES (room_id, data) KEY(room_id) VALUES (:roomId, :data)")
    fun upsert(roomId: Long, data: ByteArray): Mono<Int>

    @Modifying
    @Query("DELETE FROM APSAVES WHERE room_id = :roomId")
    fun deleteByRoomId(roomId: Long): Mono<Int>
}
