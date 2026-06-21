package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomRepository
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Service

@Service
class SaveDataService(
    private val roomRepository: RoomRepository,
) {

    suspend fun get(roomId: Long): ByteArray? =
        roomRepository.findById(roomId).awaitSingleOrNull()?.savedGameData

    suspend fun put(roomId: Long, bytes: ByteArray) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw IllegalArgumentException("Room $roomId not found")
        roomRepository.save(room.copy(savedGameData = bytes)).awaitSingle()
    }

    suspend fun clear(roomId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull() ?: return
        if (room.savedGameData == null) return
        roomRepository.save(room.copy(savedGameData = null)).awaitSingle()
    }
}
