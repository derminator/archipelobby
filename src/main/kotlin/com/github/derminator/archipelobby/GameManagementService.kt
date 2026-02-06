package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Service
class GameManagementService(
    private val supportedGameRepository: SupportedGameRepository,
    private val roomRepository: RoomRepository,
    private val yamlUploadRepository: YamlUploadRepository
) {

    @Transactional
    fun addGame(
        roomId: Long,
        gameName: String,
        isCoreVerified: Boolean,
        setupGuideUrl: String?,
        yamlFormUrl: String?,
        requiresApworld: Boolean,
        apworldId: Long?
    ): Mono<SupportedGame> = mono {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (room.state != RoomState.WAITING_FOR_PLAYERS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot add games after game has started")
        }

        val game = SupportedGame(
            roomId = roomId,
            gameName = gameName,
            isCoreVerified = isCoreVerified,
            setupGuideUrl = setupGuideUrl,
            yamlFormUrl = yamlFormUrl,
            requiresApworld = requiresApworld,
            apworldId = apworldId
        )

        supportedGameRepository.save(game).awaitSingle()
    }

    @Transactional
    fun deleteGame(gameId: Long): Mono<Void> = mono {
        supportedGameRepository.deleteById(gameId).awaitSingleOrNull()
    }

    @Transactional
    fun startGame(roomId: Long): Mono<Room> = mono {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (room.state != RoomState.WAITING_FOR_PLAYERS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Game already started")
        }

        // Check if at least one YAML has been uploaded
        val yamlCount = yamlUploadRepository.findByRoomId(roomId).count().awaitSingle()
        if (yamlCount == 0L) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "At least one player must upload a YAML before starting"
            )
        }

        val updatedRoom = room.copy(state = RoomState.GENERATING)
        roomRepository.save(updatedRoom).awaitSingle()
    }

    fun updateRoomState(roomId: Long, newState: RoomState, serverAddress: String? = null): Mono<Room> = mono {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        val updatedRoom = room.copy(state = newState, serverAddress = serverAddress)
        roomRepository.save(updatedRoom).awaitSingle()
    }
}
