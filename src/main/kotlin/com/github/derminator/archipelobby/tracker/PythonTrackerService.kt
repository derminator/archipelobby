package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.data.RoomService
import com.github.derminator.archipelobby.generator.PythonScriptRunner
import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.MultiServerProperties
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
    private val multiServerManager: MultiServerManager,
    private val multiServerProperties: MultiServerProperties,
    private val resourceLoader: ResourceLoader,
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val generatorScriptPath: String,
) : TrackerService {

    private val logger = LoggerFactory.getLogger(PythonTrackerService::class.java)
    private val cache = ConcurrentHashMap<Long, CachedData>()
    private val locationCache = ConcurrentHashMap<Pair<Long, Int>, CachedLocationData>()
    private val fetchLocks = ConcurrentHashMap<Long, Mutex>()
    private val scriptPaths = mutableListOf<Path>()

    private lateinit var trackerScriptPath: Path
    private lateinit var locationDetailsScriptPath: Path
    private lateinit var sendChecksScriptPath: Path

    private data class CachedData(val data: TrackerData, val timestamp: Instant)
    private data class CachedLocationData(val data: SlotLocations, val timestamp: Instant)

    private val jsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

    private val archipelagoDir: String by lazy {
        File(generatorScriptPath).absoluteFile.parent
    }

    @PostConstruct
    fun init() {
        trackerScriptPath = extractScript("scripts/tracker.py", "tracker", ".py")
        locationDetailsScriptPath = extractScript("scripts/location_details.py", "location_details", ".py")
        sendChecksScriptPath = extractScript("scripts/send_checks.py", "send_checks", ".py")
    }

    private fun extractScript(classpath: String, prefix: String, suffix: String): Path {
        val resource = resourceLoader.getResource("classpath:$classpath")
        val tempFile = Files.createTempFile(prefix, suffix)
        resource.inputStream.use { input ->
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
        }
        scriptPaths.add(tempFile)
        return tempFile
    }

    @PreDestroy
    fun cleanup() {
        scriptPaths.forEach { runCatching { Files.deleteIfExists(it) } }
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

            withStagedDataFiles(roomId) { multidata, save ->
                val output = withContext(Dispatchers.IO) {
                    pythonScriptRunner.run(
                        trackerScriptPath.toAbsolutePath().toString(),
                        archipelagoDir,
                        multidata.toAbsolutePath().toString(),
                        save.toAbsolutePath().toString(),
                    )
                }
                val jsonLine = output.lines().lastOrNull { it.trimStart().startsWith("{") }
                    ?: return@withStagedDataFiles null
                val data = jsonMapper.readValue(jsonLine, TrackerData::class.java)
                cache[roomId] = CachedData(data, Instant.now())
                data
            }
        }
    }

    override suspend fun getSlotLocations(roomId: Long, slot: Int): SlotLocations? {
        val cacheKey = Pair(roomId, slot)
        val cached = locationCache[cacheKey]
        if (cached != null && cached.timestamp.plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
            return cached.data
        }

        return withStagedDataFiles(roomId) { multidata, save ->
            val output = withContext(Dispatchers.IO) {
                pythonScriptRunner.run(
                    locationDetailsScriptPath.toAbsolutePath().toString(),
                    archipelagoDir,
                    multidata.toAbsolutePath().toString(),
                    save.toAbsolutePath().toString(),
                    slot.toString(),
                )
            }
            val jsonLine = output.lines().lastOrNull { it.trimStart().startsWith("{") }
                ?: return@withStagedDataFiles null
            val data = jsonMapper.readValue(jsonLine, SlotLocations::class.java)
            locationCache[cacheKey] = CachedLocationData(data, Instant.now())
            data
        }
    }

    override suspend fun sendLocationChecks(roomId: Long, slotName: String, locationIds: List<Long>): Boolean {
        if (!multiServerManager.isRunning(roomId)) return false
        val port = multiServerManager.getServerPort(roomId) ?: return false

        return try {
            val output = withContext(Dispatchers.IO) {
                pythonScriptRunner.run(
                    sendChecksScriptPath.toAbsolutePath().toString(),
                    archipelagoDir,
                    multiServerProperties.host,
                    port.toString(),
                    slotName,
                    locationIds.joinToString(","),
                )
            }
            val jsonLine = output.lines().lastOrNull { it.trimStart().startsWith("{") } ?: return false
            val result = jsonMapper.readTree(jsonLine)
            val success = result.path("success").asBoolean(false)
            if (success) {
                invalidateCache(roomId)
            } else {
                logger.warn("Failed to send checks for room {}: {}", roomId, result.path("error").asText(""))
            }
            success
        } catch (e: Exception) {
            logger.error("Failed to send location checks for room {}", roomId, e)
            false
        }
    }

    override fun invalidateCache(roomId: Long) {
        cache.remove(roomId)
        locationCache.keys.removeAll { it.first == roomId }
    }

    private suspend inline fun <T> withStagedDataFiles(
        roomId: Long,
        block: (multidata: Path, save: Path) -> T?,
    ): T? {
        val multidataBytes = roomService.getGeneratedGameBytes(roomId) ?: return null
        val saveBytes = saveDataService.get(roomId) ?: return null
        val workDir = withContext(Dispatchers.IO) { Files.createTempDirectory("archipelobby-tracker-") }
        return try {
            val multidata = workDir.resolve("game.archipelago")
            val save = workDir.resolve("game.apsave")
            withContext(Dispatchers.IO) {
                Files.write(multidata, multidataBytes)
                Files.write(save, saveBytes)
            }
            block(multidata, save)
        } catch (e: Exception) {
            logger.warn("Failed running tracker script for room {}", roomId, e)
            null
        } finally {
            withContext(Dispatchers.IO) {
                runCatching {
                    Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
                }
            }
        }
    }

    companion object {
        private const val CACHE_TTL_SECONDS = 30L
    }
}
