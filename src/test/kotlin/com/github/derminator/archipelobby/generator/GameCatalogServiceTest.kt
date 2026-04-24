package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameCatalogServiceTest {

    @Test
    fun `listCoreGames parses helper payload`(@TempDir tempDir: Path) = runBlocking {
        val service = buildService(
            tempDir = tempDir,
            stubbedOutput = """
            [python] starting up
            <<<ARCHIPELOBBY_GAMES_JSON>>>
            {"games":["A Link to the Past","Factorio"]}
            <<<END>>>
            """.trimIndent(),
        )

        val games = service.listCoreGames()

        assertEquals(listOf("A Link to the Past", "Factorio"), games.map { it.name })
        games.forEach { assertNull(it.apworldFileName, "core games must have no apworld filename") }
    }

    @Test
    fun `listCoreGames is cached across calls`(@TempDir tempDir: Path) = runBlocking {
        val runner = FakePythonScriptRunner(
            """
            <<<ARCHIPELOBBY_GAMES_JSON>>>
            {"games":["OneShot"]}
            <<<END>>>
            """.trimIndent(),
        )
        val service = buildService(tempDir, runner)

        service.listCoreGames()
        service.listCoreGames()
        service.listCoreGames()

        assertEquals(1, runner.invocationCount, "core games helper must be invoked at most once")
    }

    @Test
    fun `listCoreGames surfaces helper-reported error`(@TempDir tempDir: Path) {
        val service = buildService(
            tempDir = tempDir,
            stubbedOutput = """
            <<<ARCHIPELOBBY_GAMES_JSON>>>
            {"error":"worlds import blew up"}
            <<<END>>>
            """.trimIndent(),
        )

        val exception = assertThrows<ResponseStatusException> {
            runBlocking { service.listCoreGames() }
        }
        assert(exception.reason?.contains("worlds import blew up") == true)
    }

    @Test
    fun `listCoreGames throws when sentinels are missing`(@TempDir tempDir: Path) {
        val service = buildService(tempDir = tempDir, stubbedOutput = "nothing useful here")

        val exception = assertThrows<ResponseStatusException> {
            runBlocking { service.listCoreGames() }
        }
        assert(exception.reason?.contains("sentinel") == true)
    }

    @Test
    fun `extractApWorldGame reads game from archipelago manifest`(@TempDir tempDir: Path) {
        val service = buildService(tempDir, stubbedOutput = "unused")
        val zipBytes = buildZip(mapOf("factorio/archipelago.json" to """{"game":"Factorio"}"""))

        val game = service.extractApWorldGame(zipBytes)

        assertEquals("Factorio", game)
    }

    @Test
    fun `extractApWorldGame throws when manifest is missing`(@TempDir tempDir: Path) {
        val service = buildService(tempDir, stubbedOutput = "unused")
        val zipBytes = buildZip(mapOf("something.py" to "print('hi')"))

        val exception = assertThrows<ResponseStatusException> {
            service.extractApWorldGame(zipBytes)
        }
        assert(exception.reason?.contains("archipelago.json") == true)
    }

    @Test
    fun `extractApWorldGame throws when manifest has no game field`(@TempDir tempDir: Path) {
        val service = buildService(tempDir, stubbedOutput = "unused")
        val zipBytes = buildZip(mapOf("x/archipelago.json" to """{"compatible_version":7}"""))

        val exception = assertThrows<ResponseStatusException> {
            service.extractApWorldGame(zipBytes)
        }
        assert(exception.reason?.contains("game") == true)
    }

    private fun buildService(
        tempDir: Path,
        runner: FakePythonScriptRunner,
    ): GameCatalogService {
        val archipelagoRoot = tempDir.resolve("Archipelago").also { it.createDirectories() }
        archipelagoRoot.resolve("ModuleUpdate.py").writeText("def update(): pass")
        val generateScript = archipelagoRoot.resolve("Generate.py").also { it.writeText("print('noop')") }

        val scriptDir = tempDir.resolve("python").also { it.createDirectories() }
        val listGamesScript = scriptDir.resolve("list_games.py").also { it.writeText("print('noop')") }

        return GameCatalogService(
            listGamesScriptPath = listGamesScript.toString(),
            archipelagoScriptPath = generateScript.toString(),
            pythonScriptRunner = runner,
        )
    }

    private fun buildService(tempDir: Path, stubbedOutput: String) =
        buildService(tempDir, FakePythonScriptRunner(stubbedOutput))

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val bout = ByteArrayOutputStream()
        ZipOutputStream(bout).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bout.toByteArray()
    }
}

/**
 * PythonScriptRunner is a concrete class with a `run(scriptPath, vararg args)` method.
 * We override it here so the tests don't need Mockito's open-class-magic and so we can
 * count invocations deterministically.
 */
private class FakePythonScriptRunner(private val stubbedOutput: String) : PythonScriptRunner() {
    var invocationCount: Int = 0
        private set

    override fun run(scriptPath: String, vararg args: String): String {
        invocationCount++
        return stubbedOutput
    }
}
