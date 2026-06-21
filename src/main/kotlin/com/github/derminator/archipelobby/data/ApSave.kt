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
)

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
