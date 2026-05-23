package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.storage.UploadsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "true")
class ProcessMultiServerManager(
    private val properties: MultiServerProperties,
    private val roomRepository: RoomRepository,
    private val uploadsService: UploadsService,
    @Value($$"${archipelobby.python.executable:python}") private val pythonExecutable: String,
    @Value($$"${app.data-dir:}") private val dataDir: String,
) : MultiServerManager, SmartLifecycle {

    private val logger = LoggerFactory.getLogger(ProcessMultiServerManager::class.java)
    private val processes = ConcurrentHashMap<Long, ManagedServer>()
    private val roomLocks = ConcurrentHashMap<Long, Mutex>()
    @Volatile private var running = false

    private data class ManagedServer(val process: Process, val port: Int, val logThread: Thread)

    private val serversDir: Path by lazy {
        val dir = if (dataDir.isNotBlank()) {
            Path.of(dataDir, "servers")
        } else {
            Files.createTempDirectory("archipelobby-servers")
        }
        Files.createDirectories(dir)
        dir
    }

    private fun lockFor(roomId: Long): Mutex = roomLocks.computeIfAbsent(roomId) { Mutex() }

    override suspend fun startServer(roomId: Long) {
        lockFor(roomId).withLock {
            val existing = processes[roomId]
            if (existing != null && existing.process.isAlive) {
                logger.info("Server for room {} is already running on port {}", roomId, existing.port)
                return
            }

            val room = roomRepository.findById(roomId).awaitSingleOrNull()
                ?: throw IllegalArgumentException("Room $roomId not found")

            if (!room.isGenerated) {
                throw IllegalStateException("Room $roomId does not have a generated game")
            }

            val gameFilePath = room.generatedGameFilePath!!
            val roomServerDir = serversDir.resolve(roomId.toString())
            Files.createDirectories(roomServerDir)
            val gameFile = roomServerDir.resolve("game.archipelago")

            val gameBytes = uploadsService.getFile(gameFilePath)
            Files.write(gameFile, gameBytes)

            val port = room.serverPort ?: allocatePort()
            if (room.serverPort == null) {
                roomRepository.save(room.copy(serverPort = port)).awaitSingle()
            }

            val scriptFile = File(properties.scriptPath).absoluteFile
            val command = listOf(
                pythonExecutable,
                scriptFile.path,
                gameFile.toAbsolutePath().toString(),
                "--port", port.toString(),
                "--host", properties.host,
            )

            logger.info("Starting MultiServer for room {} on port {}: {}", roomId, port, command.joinToString(" "))

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .also {
                    it.environment()["PYTHONUNBUFFERED"] = "1"
                    it.environment()["DISPLAY"] = ""
                }
                .start()
            process.outputStream.close()

            val managed = ManagedServer(process, port, Thread.currentThread())
            processes[roomId] = managed

            val logThread = Thread({
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        logger.info("[multiserver:{}] {}", roomId, line)
                    }
                }
                val exitCode = process.waitFor()
                processes.remove(roomId, managed)
                if (exitCode != 0) {
                    logger.error("MultiServer for room {} exited with code {}", roomId, exitCode)
                } else {
                    logger.info("MultiServer for room {} stopped", roomId)
                }
            }, "multiserver-log-$roomId").apply { isDaemon = true }

            processes[roomId] = ManagedServer(process, port, logThread)
            logThread.start()

            if (!process.isAlive) {
                processes.remove(roomId)
                throw IllegalStateException("MultiServer for room $roomId exited immediately")
            }

            logger.info("MultiServer for room {} started on port {}", roomId, port)
        }
    }

    override suspend fun stopServer(roomId: Long) {
        lockFor(roomId).withLock {
            val managed = processes.remove(roomId) ?: return
            if (!managed.process.isAlive) return

            logger.info("Stopping MultiServer for room {} (port {})", roomId, managed.port)
            managed.process.destroy()
            if (!managed.process.waitFor(10, TimeUnit.SECONDS)) {
                logger.warn("MultiServer for room {} did not stop gracefully, forcing", roomId)
                managed.process.destroyForcibly()
            }
        }
    }

    override fun isRunning(roomId: Long): Boolean {
        val managed = processes[roomId] ?: return false
        return managed.process.isAlive
    }

    private suspend fun allocatePort(): Int {
        val usedPorts = mutableSetOf<Int>()
        roomRepository.findAll().asFlow().collect { room ->
            room.serverPort?.let { usedPorts.add(it) }
        }
        processes.values.forEach { usedPorts.add(it.port) }

        for (port in properties.portRangeStart..properties.portRangeEnd) {
            if (port !in usedPorts) return port
        }
        throw IllegalStateException(
            "No available ports in range ${properties.portRangeStart}-${properties.portRangeEnd}"
        )
    }

    // SmartLifecycle

    override fun start() {
        running = true
        logger.info("Auto-starting MultiServers for rooms with generated games")
        runBlocking(Dispatchers.IO) {
            roomRepository.findByGeneratedGameFilePathIsNotNull().asFlow().collect { room ->
                if (room.isGenerated && room.id != null) {
                    try {
                        startServer(room.id)
                    } catch (e: Exception) {
                        logger.error("Failed to auto-start MultiServer for room {}", room.id, e)
                    }
                }
            }
        }
    }

    override fun stop() {
        logger.info("Shutting down all MultiServers")
        val entries = processes.entries.toList()
        for ((roomId, managed) in entries) {
            if (managed.process.isAlive) {
                logger.info("Stopping MultiServer for room {}", roomId)
                managed.process.destroy()
            }
        }
        for ((roomId, managed) in entries) {
            if (!managed.process.waitFor(10, TimeUnit.SECONDS)) {
                logger.warn("MultiServer for room {} did not stop gracefully, forcing", roomId)
                managed.process.destroyForcibly()
            }
            processes.remove(roomId, managed)
        }
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE - 1
}
