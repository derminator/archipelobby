package com.github.derminator.archipelobby.game

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

@Service
class GameService(
    @Value($$"${app.archipelago-dir:archipelago}") private val archipelagoDir: String
) {

    val builtinGames: Set<String> by lazy { loadBuiltinGames() }

    private fun loadBuiltinGames(): Set<String> {
        val worldsDir = Paths.get(archipelagoDir, "worlds")
        if (Files.exists(worldsDir) && Files.isDirectory(worldsDir)) {
            val games = extractGamesFromWorldsDir(worldsDir)
            if (games.isNotEmpty()) return games
        }
        error("Archipelago directory '$archipelagoDir' does not exist or is not a directory")
    }

    private fun extractGamesFromWorldsDir(worldsDir: java.nio.file.Path): Set<String> = buildSet {
        Files.list(worldsDir)
            .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith("_") }
            .forEach { worldDir ->
                extractGameNameFromWorldDir(worldDir)?.let { add(it) }
            }
    }

    private fun extractGameNameFromWorldDir(worldDir: java.nio.file.Path): String? {
        val initPy = worldDir.resolve("__init__.py")
        if (!Files.exists(initPy)) return null
        val content = Files.readString(initPy)
        return extractGameNameFromPython(content)
            ?: extractGameNameFromConstant(content, worldDir)
    }

    /**
     * Extract a game name from a literal string assignment, e.g.:
     * ```
     *   game = "A Link to the Past"
     *   game: str = "A Link to the Past"
     *   game: ClassVar[str] = "A Link to the Past"
     * ```
     */
    internal fun extractGameNameFromPython(content: String): String? {
        val literal = Regex(
            """^\s*game\s*(?::\s*[\w\[\].,\s]+)?\s*=\s*["']([^"']+)["']\s*$""",
            RegexOption.MULTILINE
        )
        return literal.find(content)?.groupValues?.get(1)
    }

    /**
     * When the game name is stored in a module-level constant, search related files
     * in the world directory for that constant's value.
     */
    private fun extractGameNameFromConstant(content: String, worldDir: java.nio.file.Path): String? {
        val constRef = Regex(
            """^\s*game\s*(?::\s*[\w\[\].,\s]+)?\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*$""",
            RegexOption.MULTILINE
        )
        val constName = constRef.find(content)?.groupValues?.get(1) ?: return null

        // Search for the constant definition in files within the world directory
        val constDef = Regex(
            """^\s*${Regex.escape(constName)}\s*(?::\s*[\w\[\].,\s]+)?\s*=\s*["']([^"']+)["']\s*$""",
            RegexOption.MULTILINE
        )
        return Files.walk(worldDir)
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".py") }
            .map { pyFile -> constDef.find(Files.readString(pyFile))?.groupValues?.get(1) }
            .filter { it != null }
            .findFirst()
            .orElse(null)
    }

    /**
     * Extract the game name from an .apworld file (a ZIP archive).
     * The game name is found in the first top-level __init__.py using the same
     * extraction logic used for the built-in worlds.
     */
    fun extractGameNameFromApworld(apworldBytes: ByteArray): String {
        ZipInputStream(apworldBytes.inputStream()).use { zip ->
            val seen = mutableMapOf<String, ByteArrayOutputStream>()
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // Collect all Python files from the top-level package
                    val parts = entry.name.trimStart('/').split("/")
                    if (parts.size >= 2 && !parts[0].startsWith("_")) {
                        val buf = seen.getOrPut(parts[0] + "/" + parts[1]) { ByteArrayOutputStream() }
                        zip.copyTo(buf)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }

            // Prefer __init__.py for each top-level package
            for ((path, buf) in seen) {
                if (path.endsWith("/__init__.py")) {
                    val content = buf.toString(Charsets.UTF_8)
                    val name = extractGameNameFromPython(content)
                    if (name != null) return name
                }
            }
        }
        throw IllegalArgumentException("Could not find a game name in the .apworld file")
    }

    fun isBuiltinGame(gameName: String): Boolean = gameName in builtinGames
}
