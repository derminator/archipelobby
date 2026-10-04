package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class RealArchipelagoGeneratorServiceTest {

    @Test
    fun `generation limiter is reentrant within the same coroutine`() = runBlocking {
        val limiter = GenerationJobLimiter(maxConcurrentJobs = 1)

        val result = limiter.run { limiter.run { "ok" } }

        assertEquals("ok", result)
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
    fun `writes generated job files under the target directory regardless of map keys`(@TempDir tempDir: Path) {
        val target = tempDir.resolve("Players")
        val files = writeGeneratedJobFiles(
            target,
            mapOf("../../escaped" to "yaml".toByteArray(), "C:\\outside" to "yaml2".toByteArray()),
            ".yaml",
        )

        assertEquals(2, files.size)
        assertTrue(files.all { it.normalize().startsWith(target.normalize()) })
        assertTrue(files.all { it.fileName.toString().endsWith(".yaml") })
        assertTrue(!tempDir.resolve("escaped").exists())
    }

    @Test
    fun `rejects a symbolic-link generated job directory`(@TempDir tempDir: Path) {
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val target = tempDir.resolve("Players")
        val linked = runCatching { Files.createSymbolicLink(target, outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are not available on this platform")

        assertThrows<IllegalArgumentException> {
            writeGeneratedJobFiles(target, mapOf("name" to byteArrayOf(1)), ".yaml")
        }
        assertTrue(!outside.resolve("job-1.yaml").exists())
    }

    @Test
    fun `reads generated archive through an anchored output directory`(@TempDir tempDir: Path) {
        val output = Files.createDirectory(tempDir.resolve("output"))
        val expected = byteArrayOf(1, 2, 3)
        Files.write(output.resolve("generated.zip"), expected)

        assertContentEquals(expected, readGeneratedArchiveFromDirectory(output, 16))
    }

    @Test
    fun `rejects a symbolic-link generated output directory`(@TempDir tempDir: Path) {
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val linkedOutput = tempDir.resolve("output")
        val linked = runCatching { Files.createSymbolicLink(linkedOutput, outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are not available on this platform")

        assertThrows<SecurityException> {
            validateGeneratedOutputDirectory(linkedOutput)
        }
    }

    @Test
    fun `rejects a symbolic-link generated archive`(@TempDir tempDir: Path) {
        val outside = tempDir.resolve("outside.zip").also { Files.write(it, byteArrayOf(1)) }
        val linkedArchive = tempDir.resolve("generated.zip")
        val linked = runCatching { Files.createSymbolicLink(linkedArchive, outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are not available on this platform")

        assertThrows<SecurityException> {
            readGeneratedArchive(linkedArchive, 1024)
        }
    }

    @Test
    fun `rejects jobs when all generation permits are in use`() = runBlocking {
        val limiter = GenerationJobLimiter(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            limiter.run {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertThrows<ResponseStatusException> {
            val unexpected = runBlocking { limiter.run { 42 } }
            error("Limiter unexpectedly returned $unexpected")
        }

        release.complete(Unit)
        first.await()
    }

    @Test
    fun `rejects output whose final line is not a location count`() {
        assertNull(parseLocationCount("760\nunexpected output"))
    }
}
