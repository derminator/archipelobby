package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.ApSaveRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Service

@Service
class SaveDataService(
    private val apSaveRepository: ApSaveRepository,
) {

    suspend fun get(roomId: Long): ByteArray? =
        apSaveRepository.findDataByRoomId(roomId).awaitSingleOrNull()

    /**
     * Atomic single-statement upsert. Bypasses optimistic locking on `Room`
     * so the periodic save loop in the multiserver wrapper can't lose to a
     * concurrent admin-driven Room edit.
     */
    suspend fun put(roomId: Long, bytes: ByteArray) {
        apSaveRepository.upsert(roomId, bytes).awaitSingle()
    }

    suspend fun clear(roomId: Long) {
        apSaveRepository.deleteByRoomId(roomId).awaitSingleOrNull()
    }
}
