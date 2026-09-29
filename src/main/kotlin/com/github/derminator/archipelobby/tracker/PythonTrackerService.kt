package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.generator.PythonScriptRunner
import com.github.derminator.archipelobby.multiserver.InternalToken
import com.github.derminator.archipelobby.multiserver.MultiServerProperties
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
import java.util.Collections
import java.util.LinkedHashMap

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "true")
class PythonTrackerService(
    private val pythonScriptRunner: PythonScriptRunner,
    private val resourceLoader: ResourceLoader,
    private val multiServerProperties: MultiServerProperties,
    private val internalToken: InternalToken,
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val generatorScriptPath: String,
) : TrackerService {

    private val logger = LoggerFactory.getLogger(PythonTrackerService::class.java)
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<Long, CachedData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedData>): Boolean =
            size > MAX_CACHED_ROOMS
    })
    // Fixed stripes bound lock memory without removing a mutex while callers are waiting on it.
    private val fetchLocks = Array(64) { Mutex() }
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

        return fetchLocks[(roomId.hashCode() and Int.MAX_VALUE) % fetchLocks.size].withLock {
            val rechecked = cache[roomId]
            if (rechecked != null && rechecked.timestamp.plusSeconds(CACHE_TTL_SECONDS).isAfter(Instant.now())) {
                return@withLock rechecked.data
            }

            try {
                val output = withContext(Dispatchers.IO) {
                    pythonScriptRunner.runWithEnvironment(
                        trackerScriptPath.toAbsolutePath().toString(),
                        mapOf("ARCHIPELOBBY_SPRING_TOKEN" to internalToken.value),
                        archipelagoDir,
                        multiServerProperties.internalBaseUrl,
                        roomId.toString(),
                    )
                }
                val jsonLine = output.lines().lastOrNull { it.trimStart().startsWith("{") }
                    ?: error("Tracker script returned no JSON")
                val data = jsonMapper.readValue(jsonLine, TrackerData::class.java)
                if (data.error == null) cache[roomId] = CachedData(data, Instant.now())
                data
            } catch (e: Exception) {
                logger.warn("Failed to read tracker data for room {}", roomId, e)
                TrackerData(emptyList(), "Tracker unavailable. Please try again.")
            }
        }
    }

    companion object {
        private const val CACHE_TTL_SECONDS = 30L
        private const val MAX_CACHED_ROOMS = 256
    }
}
