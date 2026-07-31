package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealArchipelagoGeneratorServiceTest {

    @Test
    fun `loads APWorlds in worlds namespace when they read packaged resources`(@TempDir tempDir: Path) = runBlocking {
        val archipelagoRoot = createFakeArchipelagoRoot(tempDir)
        val service = RealArchipelagoGeneratorService(
            scriptPath = archipelagoRoot.resolve("Generate.py").toString(),
            moduleUpdateScriptPath = archipelagoRoot.resolve("ModuleUpdate.py").toString(),
            locationCountScriptPath = Path.of("python/get_location_count.py").toAbsolutePath().toString(),
            pythonScriptRunner = PythonScriptRunner(),
        )

        val locationCount = service.getLocationCount(
            "game: Manifest Resource Game\n".toByteArray(),
            mapOf("manifest_reader.apworld" to manifestReaderApworld()),
        )

        assertEquals(1, locationCount)
    }

    @Test
    fun `stages APWorlds in custom worlds directory before counting locations`(@TempDir tempDir: Path) = runBlocking {
        val archipelagoRoot = tempDir.resolve("Archipelago").also { it.createDirectories() }
        val generateScript = archipelagoRoot.resolve("Generate.py").also { it.writeText("print('noop')") }
        val locationCountScript = tempDir.resolve("python").also { it.createDirectories() }
            .resolve("get_location_count.py").also { it.writeText("print('noop')") }
        val yaml = "game: Manifest Resource Game\n".toByteArray()
        val apworld = byteArrayOf(1, 2, 3)

        val runner = object : PythonScriptRunner() {
            override fun run(scriptPath: String, vararg args: String): String {
                val workingRoot = Path.of(scriptPath).parent
                assertTrue(Files.isRegularFile(workingRoot.resolve("Generate.py")))
                assertContentEquals(apworld, workingRoot.resolve("custom_worlds/manifest-reader.apworld").readBytes())
                assertEquals(workingRoot.toString(), args[0])
                assertEquals(workingRoot.resolve("player.yaml").toString(), args[1])
                return "42"
            }
        }
        val service = RealArchipelagoGeneratorService(
            scriptPath = generateScript.toString(),
            moduleUpdateScriptPath = archipelagoRoot.resolve("ModuleUpdate.py").toString(),
            locationCountScriptPath = locationCountScript.toString(),
            pythonScriptRunner = runner,
        )

        assertEquals(42, service.getLocationCount(yaml, mapOf("manifest-reader.apworld" to apworld)))
    }

    @Test
    fun `rejects APWorld filenames that are not direct apworld children`(@TempDir tempDir: Path) {
        val archipelagoRoot = tempDir.resolve("Archipelago").also { it.createDirectories() }
        val generateScript = archipelagoRoot.resolve("Generate.py").also { it.writeText("print('noop')") }
        val locationCountScript = tempDir.resolve("python").also { it.createDirectories() }
            .resolve("get_location_count.py").also { it.writeText("print('noop')") }
        val service = RealArchipelagoGeneratorService(
            scriptPath = generateScript.toString(),
            moduleUpdateScriptPath = archipelagoRoot.resolve("ModuleUpdate.py").toString(),
            locationCountScriptPath = locationCountScript.toString(),
            pythonScriptRunner = object : PythonScriptRunner() {
                override fun run(scriptPath: String, vararg args: String): String = error("runner must not be invoked")
            },
        )

        listOf("../escaped.apworld", "/absolute.apworld", "C:\\escaped.apworld", "bad?.apworld", "CON.apworld", "not-an-apworld").forEach { filename ->
            val exception = assertThrows<ResponseStatusException> {
                runBlocking {
                    service.getLocationCount(
                        "game: Example\n".toByteArray(),
                        mapOf(filename to byteArrayOf(1)),
                    )
                }
            }
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        }

        val collision = assertThrows<ResponseStatusException> {
            runBlocking {
                service.getLocationCount(
                    "game: Example\n".toByteArray(),
                    linkedMapOf("world.apworld" to byteArrayOf(1), "WORLD.apworld" to byteArrayOf(2)),
                )
            }
        }
        assertEquals(HttpStatus.BAD_REQUEST, collision.statusCode)
    }

    @Test
    fun `rejects colliding APWorld filenames before generation`(@TempDir tempDir: Path) {
        val archipelagoRoot = tempDir.resolve("Archipelago").also { it.createDirectories() }
        val generateScript = archipelagoRoot.resolve("Generate.py").also { it.writeText("print('noop')") }
        val locationCountScript = tempDir.resolve("python").also { it.createDirectories() }
            .resolve("get_location_count.py").also { it.writeText("print('noop')") }
        val service = RealArchipelagoGeneratorService(
            scriptPath = generateScript.toString(),
            moduleUpdateScriptPath = archipelagoRoot.resolve("ModuleUpdate.py").toString(),
            locationCountScriptPath = locationCountScript.toString(),
            pythonScriptRunner = object : PythonScriptRunner() {
                override fun run(scriptPath: String, vararg args: String): String = error("runner must not be invoked")
            },
        )

        val exception = assertThrows<ResponseStatusException> {
            runBlocking {
                service.generate(
                    emptyMap(),
                    linkedMapOf("world.apworld" to byteArrayOf(1), "WORLD.apworld" to byteArrayOf(2)),
                )
            }
        }
        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
    }

    @Test
    fun `parses location count after world generation warning`() {
        val output = """
            WARNING:root:Manual_ResidentEvil4_VincentsSin has more items than locations. 400 non-progression items will be removed at random.
            760
        """.trimIndent()

        assertEquals(760, parseLocationCount(output))
    }

    @Test
    fun `rejects output whose final line is not a location count`() {
        assertNull(parseLocationCount("760\nunexpected output"))
    }

    private fun createFakeArchipelagoRoot(tempDir: Path): Path {
        val root = tempDir.resolve("Archipelago").also { it.createDirectories() }
        root.resolve("Generate.py").writeText("print('noop')")
        root.resolve("BaseClasses.py").writeText(
            """
            class PlandoOptions:
                none = 0

            class MultiWorld:
                def __init__(self, players):
                    self.players = players
                def get_locations(self, player):
                    return [type("Location", (), {"address": 1})()]

            class CollectionState:
                def __init__(self, multiworld):
                    self.multiworld = multiworld
            """.trimIndent(),
        )
        root.resolve("yaml.py").writeText(
            """
            def safe_load(stream):
                return {"game": "Manifest Resource Game", "name": "Tester"}
            """.trimIndent(),
        )
        val worlds = root.resolve("worlds").also { it.createDirectories() }
        worlds.resolve("AutoWorld.py").writeText("class AutoWorldRegister:\n    world_types = {}\n")
        worlds.resolve("__init__.py").writeText(
            """
            import importlib
            import sys
            import zipimport
            from pathlib import Path

            specs = {}
            for archive in (Path(__file__).parent.parent / "custom_worlds").glob("*.apworld"):
                importer = zipimport.zipimporter(str(archive))
                name = f"worlds.{archive.stem}"
                specs[name] = importer.find_spec(name)

            class Finder:
                def find_spec(self, fullname, path=None, target=None):
                    return specs.get(fullname)

            sys.meta_path.insert(0, Finder())
            for name in specs:
                importlib.import_module(name)
            """.trimIndent(),
        )
        return root
    }

    private fun manifestReaderApworld(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.writeEntry(
                "manifest_reader/__init__.py",
                """
                import json
                import pkgutil
                from worlds.AutoWorld import AutoWorldRegister

                manifest = json.loads(pkgutil.get_data(__package__, "archipelago.json"))

                class ManifestReaderWorld:
                    def __init__(self, multiworld, player):
                        self.multiworld = multiworld
                        self.player = player
                    def generate_early(self): pass
                    def create_regions(self): pass
                    def create_items(self): pass
                    def generate_basic(self): pass
                    def pre_fill(self): pass

                AutoWorldRegister.world_types[manifest["game"]] = ManifestReaderWorld
                """.trimIndent(),
            )
            zip.writeEntry("manifest_reader/archipelago.json", "{\"game\": \"Manifest Resource Game\"}")
        }
        bytes.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }
}
