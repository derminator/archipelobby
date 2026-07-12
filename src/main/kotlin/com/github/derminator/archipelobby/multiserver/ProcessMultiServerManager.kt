package com.github.derminator.archipelobby.multiserver

import com.github.derminator.archipelobby.data.RoomRepository
import com.github.derminator.archipelobby.generator.PythonScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "true")
class ProcessMultiServerManager(
    private val properties: MultiServerProperties,
    private val roomRepository: RoomRepository,
    private val pythonScriptRunner: PythonScriptRunner,
    private val internalToken: InternalToken,
) : MultiServerManager, SmartLifecycle {

    private val logger = LoggerFactory.getLogger(ProcessMultiServerManager::class.java)
    private val processes = ConcurrentHashMap<Long, ManagedServer>()
    private val roomLocks = ConcurrentHashMap<Long, Mutex>()
    private val allocationMutex = Mutex()
    private val pendingPorts = ConcurrentHashMap.newKeySet<Int>()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    private data class ManagedServer(val process: Process, val port: Int, val logThread: Thread)

    private fun lockFor(roomId: Long): Mutex = roomLocks.computeIfAbsent(roomId) { Mutex() }

    private fun archipelagoDir(): String =
        File(properties.scriptPath).absoluteFile.parent ?: "."

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

            val port = allocatePort()
            try {
                logger.info("Starting MultiServer for room {} on port {}", roomId, port)

                val process = pythonScriptRunner.runInBackground(
                    scriptPath = properties.wrapperScriptPath,
                    extraEnv = mapOf("ARCHIPELOBBY_SPRING_TOKEN" to internalToken.value),
                    "--spring-url", properties.internalBaseUrl,
                    "--room-id", roomId.toString(),
                    "--archipelago-dir", archipelagoDir(),
                    "--port", port.toString(),
                    "--host", properties.host,
                )

                lateinit var managed: ManagedServer
                val logThread = Thread({
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            logger.info("[multiserver:{}] {}", roomId, line)
                        }
                    }
                    val exitCode = process.waitFor()
                    // CAS remove so a racing startServer that already put a fresh
                    // ManagedServer for this room isn't clobbered.
                    processes.remove(roomId, managed)
                    if (exitCode != 0) {
                        logger.error("MultiServer for room {} exited with code {}", roomId, exitCode)
                    } else {
                        logger.info("MultiServer for room {} stopped", roomId)
                    }
                }, "multiserver-log-$roomId").apply { isDaemon = true }

                managed = ManagedServer(process, port, logThread)
                processes[roomId] = managed
                logThread.start()

                if (!process.isAlive) {
                    processes.remove(roomId, managed)
                    throw IllegalStateException("MultiServer for room $roomId exited immediately")
                }

                logger.info("MultiServer for room {} started on port {}", roomId, port)
            } finally {
                pendingPorts.remove(port)
            }
        }
    }

    override suspend fun stopServer(roomId: Long) {
        lockFor(roomId).withLock {
            val managed = processes.remove(roomId) ?: return
            if (!managed.process.isAlive) return

            logger.info("Stopping MultiServer for room {} (port {})", roomId, managed.port)
            managed.process.destroy()
            val stopped = withContext(Dispatchers.IO) {
                managed.process.waitFor(10, TimeUnit.SECONDS)
            }
            if (!stopped) {
                logger.warn("MultiServer for room {} did not stop gracefully, forcing", roomId)
                managed.process.destroyForcibly()
            }
        }
    }

    override fun isRunning(roomId: Long): Boolean {
        val managed = processes[roomId] ?: return false
        return managed.process.isAlive
    }

    override fun getServerPort(roomId: Long): Int? {
        val managed = processes[roomId] ?: return null
        if (!managed.process.isAlive) {
            // The log thread removes the entry when the process exits, but that
            // happens asynchronously. Drop the stale entry here so we never hand
            // out a port for a dead (or since-reused) process.
            processes.remove(roomId, managed)
            return null
        }
        return managed.port
    }

    private suspend fun allocatePort(): Int = allocationMutex.withLock {
        val usedPorts = processes.values.mapTo(mutableSetOf()) { it.port }
        usedPorts.addAll(pendingPorts)
        val port = (properties.portRangeStart..properties.portRangeEnd).firstOrNull { it !in usedPorts }
            ?: error("No available ports in range ${properties.portRangeStart}-${properties.portRangeEnd}")
        // Reserve the port so a concurrent allocation for a different room doesn't
        // pick it before this caller can put a ManagedServer in `processes`.
        pendingPorts.add(port)
        port
    }

    // SmartLifecycle

    override fun start() {
        running = true
        logger.info("Auto-starting MultiServers for rooms with generated games")
        lifecycleScope.launch {
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

    override fun stop(callback: Runnable) {
        logger.info("Shutting down all MultiServers")
        lifecycleScope.launch {
            try {
                stopAll()
            } finally {
                running = false
                callback.run()
            }
        }
    }

    override fun stop() {
        // SmartLifecycle.stop(Runnable) is preferred; this fallback supports direct
        // invocation of the synchronous variant.
        runBlocking { stopAll() }
        running = false
    }

    private suspend fun stopAll() = coroutineScope {
        processes.keys.toList().forEach { roomId ->
            launch { runCatching { stopServer(roomId) } }
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE - 1
}
