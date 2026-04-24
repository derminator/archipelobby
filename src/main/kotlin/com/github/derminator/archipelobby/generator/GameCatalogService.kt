package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Enumerates the games an Archipelago install can generate for.
 *
 * Core games (bundled in the Archipelago submodule under `worlds/`) are
 * discovered by running a Python helper that imports Archipelago's own
 * `AutoWorldRegister`. Per-apworld games are extracted by reading the
 * apworld's `archipelago.json` manifest directly from the zip — cheaper than
 * a subprocess and sufficient because the manifest's `game` field is the
 * authoritative source Archipelago itself consults.
 */
@Service
class GameCatalogService(
    @Value($$"${archipelobby.archipelago.list-games-script-path:python/list_games.py}")
    private val listGamesScriptPath: String,
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}")
    private val archipelagoScriptPath: String,
    private val pythonScriptRunner: PythonScriptRunner,
) {

    private val jsonMapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    private val coreGamesCache = AtomicReference<List<GameInfo>>()

    suspend fun listCoreGames(): List<GameInfo> {
        coreGamesCache.get()?.let { return it }
        val fresh = withContext(Dispatchers.IO) { runCoreHelper() }
        coreGamesCache.compareAndSet(null, fresh)
        return coreGamesCache.get() ?: fresh
    }

    /**
     * Extracts the single game name an apworld zip registers. Reads the
     * archipelago.json manifest in-process; does not run Python. Throws
     * BAD_REQUEST if the zip is missing a manifest or the `game` field.
     */
    fun extractApWorldGame(apworldBytes: ByteArray): String {
        ByteArrayInputStream(apworldBytes).use { bis ->
            ZipInputStream(bis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("archipelago.json")) {
                        val manifest = jsonMapper.readValue(
                            zis.readAllBytes(),
                            ApWorldManifest::class.java,
                        )
                        val game = manifest.game?.takeIf { it.isNotBlank() }
                            ?: throw ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "APWorld manifest is missing a 'game' field",
                            )
                        return game
                    }
                    entry = zis.nextEntry
                }
            }
        }
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "APWorld is missing an archipelago.json manifest — upload a newer apworld that declares its game.",
        )
    }

    private fun runCoreHelper(): List<GameInfo> {
        val archipelagoRoot = File(archipelagoScriptPath).absoluteFile.parentFile
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Cannot locate Archipelago root from script path $archipelagoScriptPath",
            )
        val scriptSrc = File(listGamesScriptPath).absoluteFile
        if (!scriptSrc.isFile) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "list_games helper not found at ${scriptSrc.path}",
            )
        }

        val workDir = Files.createTempDirectory("archipelago-list-games-").toFile()
        try {
            archipelagoRoot.copyRecursively(workDir, overwrite = true)
            workDir.resolve("custom_worlds").mkdirs()
            val scriptInWorkDir = workDir.resolve(scriptSrc.name)
            scriptSrc.copyTo(scriptInWorkDir, overwrite = true)

            val output = pythonScriptRunner.run(scriptInWorkDir.absolutePath, "core")
            return parseCoreOutput(output)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun parseCoreOutput(output: String): List<GameInfo> {
        val start = output.indexOf(SENTINEL_START)
        val end = output.indexOf(SENTINEL_END, startIndex = start + 1)
        if (start < 0 || end < 0) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "list_games helper did not emit sentinel markers. Output: ${output.takeLast(2000)}",
            )
        }
        val json = output.substring(start + SENTINEL_START.length, end).trim()
        val payload = jsonMapper.readValue(json, CoreGamesPayload::class.java)
        payload.error?.let { error ->
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "list_games helper reported error: $error",
            )
        }
        val games = payload.games ?: throw ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "list_games helper JSON payload missing 'games' field",
        )
        return games.map { GameInfo(it) }
    }

    companion object {
        private const val SENTINEL_START = "<<<ARCHIPELOBBY_GAMES_JSON>>>"
        private const val SENTINEL_END = "<<<END>>>"
    }
}

internal data class CoreGamesPayload(
    val games: List<String>? = null,
    val error: String? = null,
)

internal data class ApWorldManifest(val game: String? = null)

/**
 * A game that can appear in a player's YAML `game:` field. `apworldFileName`
 * is null for core games; non-null when the game is contributed by an
 * uploaded apworld in the current room (the filename is the persistence key,
 * used by the room page to link back to the uploaded file).
 */
data class GameInfo(
    val name: String,
    val apworldFileName: String? = null,
)
