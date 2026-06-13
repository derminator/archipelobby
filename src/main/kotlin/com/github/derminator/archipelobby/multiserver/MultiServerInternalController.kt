package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomService
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/internal/multiserver/{token}")
class MultiServerInternalController(
    private val internalToken: InternalToken,
    private val roomService: RoomService,
    private val saveDataService: SaveDataService,
) {

    private fun checkToken(token: String) {
        if (token != internalToken.value) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping("/game/{roomId}")
    fun getGameData(
        @PathVariable token: String,
        @PathVariable roomId: Long,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        checkToken(token)
        val bytes = roomService.getGeneratedGameBytes(roomId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated game for room $roomId")
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @GetMapping("/save/{roomId}")
    fun getSaveData(
        @PathVariable token: String,
        @PathVariable roomId: Long,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        checkToken(token)
        val bytes = saveDataService.get(roomId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @PutMapping("/save/{roomId}")
    fun putSaveData(
        @PathVariable token: String,
        @PathVariable roomId: Long,
        @RequestBody body: ByteArray,
    ): Mono<ResponseEntity<Void>> = mono {
        checkToken(token)
        saveDataService.put(roomId, body)
        ResponseEntity.noContent().build()
    }
}
