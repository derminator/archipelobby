package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.generator.PythonScriptRunner
import com.github.derminator.archipelobby.multiserver.SaveDataService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.module.kotlin.KotlinModule
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "true")
class PythonTrackerService(
    private val pythonScriptRunner: PythonScriptRunner,
    private val roomService: RoomService,
    private val saveDataService: SaveDataService,
    private val resourceLoader: ResourceLoader,
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val generatorScriptPath: String,
) : TrackerService {

    private val logger = LoggerFactory.getLogger(PythonTrackerService::class.java)
    private val cache = ConcurrentHashMap<Long, CachedData>()
    private val fetchLocks = ConcurrentHashMap<Long, Mutex>()
    private lateinit var trackerScriptPath: Path

    private data class CachedData(val data: TrackerData, val timestamp: Instant)

    private val jsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

    private val archipelagoDir: String by lazy {
        File(generatorScriptPath).absoluteFile.parent
    }

    @PostConstruct
    fun init() {
        val resource = resourceLoader.getResource("classpath:scripts/tracker.py")
        val tempFile = Files.createTempFile("tracker", ".py")
        resource.inputStream.use { input ->
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
        }
        trackerScriptPath = tempFile
    }

    @PreDestroy
    fun cleanup() {
        runCatching { Files.deleteIfExists(trackerScriptPath) }
    }

    override suspend fun getTrackerData(roomId: Long): TrackerData? {
        val cached = cache[roomId]
        if (cached != null && cached.timestamp.plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
            return cached.data
        }

        return fetchLocks.computeIfAbsent(roomId) { Mutex() }.withLock {
            val rechecked = cache[roomId]
            if (rechecked != null && rechecked.timestamp.plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
                return@withLock rechecked.data
            }

            val multidataBytes = roomService.getGeneratedGameBytes(roomId) ?: return@withLock null
            val saveBytes = saveDataService.get(roomId) ?: return@withLock null

            val workDir = withContext(Dispatchers.IO) { Files.createTempDirectory("archipelobby-tracker-") }
            try {
                val multidataFile = workDir.resolve("game.archipelago")
                val saveFile = workDir.resolve("game.apsave")
                withContext(Dispatchers.IO) {
                    Files.write(multidataFile, multidataBytes)
                    Files.write(saveFile, saveBytes)
                }
                val output = withContext(Dispatchers.IO) {
                    pythonScriptRunner.run(
                        trackerScriptPath.toAbsolutePath().toString(),
                        archipelagoDir,
                        multidataFile.toAbsolutePath().toString(),
                        saveFile.toAbsolutePath().toString(),
                    )
                }
                val jsonLine = output.lines().lastOrNull { it.trimStart().startsWith("{") } ?: return@withLock null
                val data = jsonMapper.readValue(jsonLine, TrackerData::class.java)
                cache[roomId] = CachedData(data, Instant.now())
                data
            } catch (e: Exception) {
                logger.warn("Failed to read tracker data for room {}", roomId, e)
                null
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching {
                        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
                    }
                }
            }
        }
    }

    companion object {
        private const val CACHE_TTL_SECONDS = 30L
    }
}
