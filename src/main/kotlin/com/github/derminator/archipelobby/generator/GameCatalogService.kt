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
 * Lists the games an Archipelago installation can generate for.
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
     * Extracts the single game name that an apworld zip registers.
     *
     * Fast path: reads the archipelago.json manifest in-process (no Python).
     * Fallback: if the manifest is absent or its `game` field is blank, runs
     * the same Python helper used for core games with the apworld placed in
     * custom_worlds/, then diffs the result against the core game list.
     */
    suspend fun extractApWorldGame(apworldBytes: ByteArray, fileName: String): String {
        val manifestGame = readManifestGame(apworldBytes)
        if (manifestGame != null) return manifestGame

        val coreGames = listCoreGames().map { it.name }.toSet()
        return withContext(Dispatchers.IO) { runApWorldHelper(apworldBytes, fileName, coreGames) }
    }

    private fun readManifestGame(apworldBytes: ByteArray): String? {
        ByteArrayInputStream(apworldBytes).use { bis ->
            ZipInputStream(bis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("archipelago.json")) {
                        return runCatching {
                            val manifest = jsonMapper.readValue(
                                zis.readAllBytes(),
                                ApWorldManifest::class.java,
                            )
                            manifest.game?.takeIf { it.isNotBlank() }
                        }.getOrNull()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return null
    }

    private fun runApWorldHelper(apworldBytes: ByteArray, fileName: String, coreGames: Set<String>): String {
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

        val workDir = Files.createTempDirectory("archipelago-apworld-games-").toFile()
        try {
            archipelagoRoot.copyRecursively(workDir, overwrite = true)
            val customWorldsDir = workDir.resolve("custom_worlds").also { it.mkdirs() }
            customWorldsDir.resolve(fileName).writeBytes(apworldBytes)
            val scriptInWorkDir = workDir.resolve(scriptSrc.name)
            scriptSrc.copyTo(scriptInWorkDir, overwrite = true)

            val output = pythonScriptRunner.run(scriptInWorkDir.absolutePath, "core")
            val allGames = parseCoreOutput(output).map { it.name }.toSet()
            val apWorldGames = allGames - coreGames

            return apWorldGames.singleOrNull()
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    if (apWorldGames.isEmpty())
                        "APWorld did not register any new game"
                    else
                        "APWorld registered multiple games: ${apWorldGames.sorted().joinToString()}",
                )
        } finally {
            workDir.deleteRecursively()
        }
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
