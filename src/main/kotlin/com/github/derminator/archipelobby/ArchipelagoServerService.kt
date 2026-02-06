package com.github.derminator.archipelobby

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

@Service
class ArchipelagoServerService(
    private val roomRepository: RoomRepository,
    private val yamlUploadRepository: YamlUploadRepository,
    private val gameManagementService: GameManagementService
) {
    private val uploadBasePath = Paths.get("uploads")
    private val runningServers = ConcurrentHashMap<Long, Process>()

    // Default port for Archipelago servers
    private val basePort = 38281

    fun generateMultiworld(roomId: Long): Mono<Path> = mono {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (room.state != RoomState.GENERATING) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Room must be in GENERATING state")
        }

        // Get all YAML files for this room
        val roomPath = uploadBasePath.resolve("room_$roomId")
        val yamlsPath = roomPath.resolve("yamls")
        val outputPath = roomPath.resolve("output")
        Files.createDirectories(outputPath)

        // Copy all YAMLs to a temporary directory for generation
        val yamlFiles = yamlsPath.toFile().listFiles { file ->
            file.extension in listOf("yaml", "yml")
        } ?: emptyArray()

        if (yamlFiles.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No YAML files found")
        }

        // TODO: Call ArchipelagoGenerate.exe or similar to generate the multiworld
        // This is a placeholder - you'll need to integrate with your Archipelago installation
        // Example: ProcessBuilder("ArchipelagoGenerate.exe", "--outputpath", outputPath.toString(), ...yamls)

        val multiworldFile = outputPath.resolve("AP_${room.id}.zip")

        // For now, return the path where the multiworld would be generated
        multiworldFile
    }

    fun startServer(roomId: Long): Mono<String> = mono {
        val room = roomRepository.findById(roomId).awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")

        if (runningServers.containsKey(roomId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Server already running for this room")
        }

        val roomPath = uploadBasePath.resolve("room_$roomId")
        val outputPath = roomPath.resolve("output")
        val multiworldFile = outputPath.resolve("AP_${room.id}.zip")

        if (!multiworldFile.toFile().exists()) {
            // Generate first
            generateMultiworld(roomId).awaitSingleOrNull()
        }

        // Calculate port (you might want a more sophisticated port allocation)
        val port = basePort + roomId.toInt()

        // TODO: Start the Archipelago server process
        // Example: ProcessBuilder("ArchipelagoServer.exe", "--port", port.toString(), multiworldFile.toString())
        // For now, this is a placeholder

        val serverAddress = "localhost:$port"

        // Update room state and server address
        gameManagementService.updateRoomState(roomId, RoomState.RUNNING, serverAddress).awaitSingleOrNull()

        serverAddress
    }

    fun stopServer(roomId: Long): Mono<Void> {
        return mono {
            val process = runningServers.remove(roomId)
            process?.destroy()

            // Update room state
            gameManagementService.updateRoomState(roomId, RoomState.COMPLETED).awaitSingleOrNull()
            null
        }.then()
    }

    fun getServerStatus(roomId: Long): Mono<ServerStatus> = mono {
        val isRunning = runningServers.containsKey(roomId)
        val process = runningServers[roomId]
        val isAlive = process?.isAlive ?: false

        ServerStatus(isRunning, isAlive)
    }
}

data class ServerStatus(val isRunning: Boolean, val isAlive: Boolean)
