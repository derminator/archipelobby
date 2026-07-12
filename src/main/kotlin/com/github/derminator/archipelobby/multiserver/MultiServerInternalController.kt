package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomService
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.security.MessageDigest

@RestController
@RequestMapping("/internal/multiserver")
class MultiServerInternalController(
    private val internalToken: InternalToken,
    private val roomService: RoomService,
    private val saveDataService: SaveDataService,
) {

    private fun checkAuth(authHeader: String?) {
        val expected = "Bearer ${internalToken.value}"
        // Constant-time comparison so a matching token prefix can't be recovered
        // by timing the response.
        val authorized = MessageDigest.isEqual(
            (authHeader ?: "").toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
        if (!authorized) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping("/game/{roomId}")
    fun getGameData(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @PathVariable roomId: Long,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        checkAuth(auth)
        val bytes = roomService.getGeneratedGameBytes(roomId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No generated game for room $roomId")
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @GetMapping("/save/{roomId}")
    fun getSaveData(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @PathVariable roomId: Long,
    ): Mono<ResponseEntity<ByteArray>> = mono {
        checkAuth(auth)
        val bytes = saveDataService.get(roomId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(bytes)
    }

    @PutMapping("/save/{roomId}")
    fun putSaveData(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) auth: String?,
        @PathVariable roomId: Long,
        @RequestBody body: ByteArray,
    ): Mono<ResponseEntity<Void>> = mono {
        checkAuth(auth)
        saveDataService.put(roomId, body)
        ResponseEntity.noContent().build()
    }
}
