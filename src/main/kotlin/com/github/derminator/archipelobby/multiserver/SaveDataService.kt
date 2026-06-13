package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Service

@Service
class SaveDataService(
    private val roomRepository: RoomRepository,
    private val uploadsService: UploadsService,
) {

    suspend fun get(roomId: Long): ByteArray? {
        val room = roomRepository.findById(roomId).awaitSingleOrNull() ?: return null
        val path = room.savedGameFilePath ?: return null
        return if (uploadsService.fileExists(path)) uploadsService.getFile(path) else null
    }

    suspend fun put(roomId: Long, bytes: ByteArray) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw IllegalArgumentException("Room $roomId not found")
        val existing = room.savedGameFilePath
        val newPath = uploadsService.saveFile(bytes, "${roomId}.apsave")
        roomRepository.save(room.copy(savedGameFilePath = newPath)).awaitSingle()
        if (existing != null && existing != newPath) {
            runCatching { uploadsService.deleteFile(existing) }
        }
    }

    suspend fun clear(roomId: Long) {
        val room = roomRepository.findById(roomId).awaitSingleOrNull() ?: return
        val path = room.savedGameFilePath ?: return
        roomRepository.save(room.copy(savedGameFilePath = null)).awaitSingle()
        runCatching { uploadsService.deleteFile(path) }
    }
}
