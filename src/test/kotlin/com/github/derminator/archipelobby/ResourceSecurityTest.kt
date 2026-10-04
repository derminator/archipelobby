package com.github.derminator.archipelobby

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ResourceSecurityTest {

    @Test
    fun `rejects non-positive archive limits`() {
        assertThrows<IllegalArgumentException> { ZipResourceLimits(maxEntryBytes = 0) }
        assertThrows<IllegalArgumentException> {
            createDownloadZip(emptyList(), maxUncompressedBytes = 1, maxArchiveBytes = 0, maxEntries = 1)
        }
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun `directory entry payloads count toward decompression limits`() {
        val zip = zipOf("directory/" to ByteArray(33))

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(zip, ZipResourceLimits(maxEntryBytes = 32, maxTotalBytes = 64))
        }

        assertContains(error.reason.orEmpty(), "entry size")
    }

    @Test
    fun `rejects zip entries larger than the configured limit`() {
        val zip = zipOf("large.archipelago" to ByteArray(33) { 'A'.code.toByte() })

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(zip, ZipResourceLimits(maxEntryBytes = 32, maxTotalBytes = 64))
        }

        assertContains(error.reason.orEmpty(), "entry size")
    }

    @Test
    fun `rejects zip archives with too many entries`() {
        val zip = zipOf(
            "game.archipelago" to byteArrayOf(1),
            "spoiler.txt" to byteArrayOf(2),
            "patch.apz3" to byteArrayOf(3),
        )

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(zip, ZipResourceLimits(maxEntries = 2))
        }

        assertContains(error.reason.orEmpty(), "entry count")
    }

    @Test
    fun `rejects zip archives larger than the total decompressed limit`() {
        val zip = zipOf(
            "game.archipelago" to ByteArray(20),
            "spoiler.txt" to ByteArray(20),
        )

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(zip, ZipResourceLimits(maxEntryBytes = 32, maxTotalBytes = 32))
        }

        assertContains(error.reason.orEmpty(), "decompressed size")
    }

    @Test
    fun `rejects suspicious compression ratios`() {
        val zip = zipOf("bomb.archipelago" to ByteArray(10_000) { 0 })

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(
                zip,
                ZipResourceLimits(
                    maxEntryBytes = 20_000,
                    maxTotalBytes = 20_000,
                    maxCompressionRatio = 2,
                ),
            )
        }

        assertContains(error.reason.orEmpty(), "compression ratio")
    }

    @Test
    fun `selected ZIP entry still validates entries that follow it`() {
        val zip = zipOf(
            "archipelago.json" to "{}".toByteArray(),
            "payload.bin" to ByteArray(4096),
        )
        val limits = ZipResourceLimits(
            maxEntryBytes = 1024,
            maxTotalBytes = 8192,
            maxCompressionRatio = 10_000,
        )

        assertThrows<ResponseStatusException> {
            findZipEntryBytes(zip, limits) { it.name == "archipelago.json" }
        }
    }

    @Test
    fun `matching ZIP entry uses its stricter streaming limit`() {
        val zip = zipOf("archipelago.json" to ByteArray(33))

        val error = assertThrows<ResponseStatusException> {
            findZipEntryBytes(
                zip,
                ZipResourceLimits(maxEntryBytes = 1024, maxTotalBytes = 2048),
                maxMatchingEntryBytes = 32,
            ) { it.name == "archipelago.json" }
        }

        assertContains(error.reason.orEmpty(), "entry size")
    }

    @Test
    fun `rejects duplicate ZIP entry names case insensitively`() {
        val zip = zipOf(
            "archipelago.json" to "{}".toByteArray(),
            "ARCHIPELAGO.JSON" to "{}".toByteArray(),
        )

        assertThrows<ResponseStatusException> {
            findZipEntryBytes(zip) { it.name.equals("archipelago.json", ignoreCase = true) }
        }
    }

    @Test
    fun `rejects local and central ZIP filename mismatches`() {
        val zip = zipOf("archipelago.json" to "{}".toByteArray())
        zip[30] = 'x'.code.toByte()

        assertThrows<ResponseStatusException> {
            findZipEntryBytes(zip) { true }
        }
    }

    @Test
    fun `rejects traversal and absolute ZIP entry names`() {
        listOf(
            "../evil.apworld",
            "/absolute.apworld",
            "C:/absolute.apworld",
            "C:drive-relative.apworld",
            "\\\\server\\share\\evil.apworld",
            "\\\\?\\C:\\device\\evil.apworld",
            "CON",
            "NUL.txt",
            "COM1.apworld",
            "nested/file:stream.apworld",
            "trailing-dot.",
        ).forEach { name ->
            val zip = zipOf(name to byteArrayOf(1))
            assertThrows<ResponseStatusException> {
                findZipEntryBytes(zip) { true }
            }
        }
    }

    @Test
    fun `rejects duplicate patch basenames after sanitization`() {
        val zip = zipOf(
            "one/patch.apz3" to byteArrayOf(1),
            "two/PATCH.apz3" to byteArrayOf(2),
        )

        val error = assertThrows<ResponseStatusException> {
            extractFilesFromZip(zip)
        }

        assertContains(error.reason.orEmpty(), "duplicate")
    }

    @Test
    fun `sanitizes archive paths and control characters`() {
        assertEquals("evil__name.yaml", safeDownloadFileName("../evil\r\nname", ".yaml"))
        assertEquals("world.apworld", safeDownloadFileName("..\\world.apworld", ".apworld"))
    }

    @Test
    fun `bounds generated download archives`() {
        val error = assertThrows<ResponseStatusException> {
            createDownloadZip(
                listOf("Players/player.yaml" to ByteArray(128) { it.toByte() }),
                maxUncompressedBytes = 256,
                maxArchiveBytes = 32,
                maxEntries = 10,
            )
        }

        assertContains(error.reason.orEmpty(), "archive size")
    }
}
