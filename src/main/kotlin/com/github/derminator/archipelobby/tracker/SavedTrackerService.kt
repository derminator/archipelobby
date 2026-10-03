package com.github.derminator.archipelobby.tracker

import com.github.derminator.archipelobby.multiserver.MultiServerManager
import com.github.derminator.archipelobby.multiserver.SaveDataService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

@Service
@ConditionalOnProperty("archipelobby.multiserver.enabled", havingValue = "true")
class SavedTrackerService(
    private val saveDataService: SaveDataService,
    private val multiServerManager: MultiServerManager,
) : TrackerService {
    private val logger = LoggerFactory.getLogger(SavedTrackerService::class.java)
    private val jsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build()

    override suspend fun getTrackerData(roomId: Long): TrackerData {
        if (!multiServerManager.isRunning(roomId)) {
            return TrackerData(emptyList(), "Tracker unavailable while the server is stopped.")
        }
        return try {
            val bytes = saveDataService.get(roomId) ?: error("No saved tracker data")
            val envelope = jsonMapper.readTree(bytes)
            val version = envelope.get("formatVersion")
            require(version != null && version.isIntegralNumber && version.canConvertToInt() && version.asInt() == 1) {
                "Unsupported JSON save format"
            }
            val projection = envelope.get("tracker") ?: error("Missing tracker projection")
            val snapshot = jsonMapper.treeToValue(projection, TrackerSnapshot::class.java)
            TrackerData(snapshot.players.map { player ->
                PlayerProgress(
                    player.slot, player.name, player.game, player.checksDone, player.checksTotal,
                    when (player.statusCode) {
                        0 -> "Disconnected"
                        5 -> "Connected"
                        10 -> "Ready"
                        20 -> "Playing"
                        30 -> "Goal Completed"
                        else -> "Unknown"
                    },
                )
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The save contains passwords and stored game data. Do not log parse
            // exceptions, which can include input excerpts.
            logger.warn("Failed to read tracker data for room {} ({})", roomId, e.javaClass.simpleName)
            TrackerData(emptyList(), "Tracker unavailable. Please try again.")
        }
    }

    private data class TrackerSnapshot(val players: List<SavedPlayer>)

    private data class SavedPlayer(
        val slot: Int,
        val name: String,
        val game: String,
        val checksDone: Int,
        val checksTotal: Int,
        val statusCode: Int,
    )
}
