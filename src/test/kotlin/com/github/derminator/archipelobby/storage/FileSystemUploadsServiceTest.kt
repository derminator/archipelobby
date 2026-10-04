package com.github.derminator.archipelobby.storage

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemUploadsServiceTest {

    @Test
    fun `rejects a symbolic-link upload root`(@TempDir tempDir: Path) {
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val linked = runCatching { Files.createSymbolicLink(tempDir.resolve("uploads"), outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are not available on this platform")

        val service = FileSystemUploadsService(tempDir.toString())
        assertThrows<IllegalArgumentException> { service.init() }
    }

    @Test
    fun `rejects upload root replacement after initialization`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()
        val uploads = tempDir.resolve("uploads")
        val original = tempDir.resolve("original-uploads")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.move(uploads, original)
        val linked = runCatching { Files.createSymbolicLink(uploads, outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are not available on this platform")
        val outsideFile = outside.resolve("attacker-file").also { Files.write(it, byteArrayOf(1)) }

        assertThrows<SecurityException> { runBlocking { service.getFile(uploads.resolve(outsideFile.fileName).toString()) } }
        assertThrows<SecurityException> { runBlocking { service.saveFile(byteArrayOf(2), "new.yaml") } }
    }

    @Test
    fun `stores uploads under generated names regardless of client filename`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()

        val storedPath = Path.of(service.saveFile("safe".toByteArray(), "../../outside.yaml"))

        assertTrue(storedPath.normalize().startsWith(tempDir.resolve("uploads").normalize()))
        assertTrue(storedPath.isRegularFile())
        assertFalse(tempDir.resolve("outside.yaml").exists())
        assertContentEquals("safe".toByteArray(), service.getFile(storedPath.toString()))
    }

    @Test
    fun `rejects missing nested paths under the upload directory`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()
        val nested = tempDir.resolve("uploads").resolve("nested").resolve("missing")

        assertThrows<SecurityException> { service.getFile(nested.toString()) }
        assertThrows<SecurityException> { service.deleteFile(nested.toString()) }
        assertThrows<SecurityException> { service.fileExists(nested.toString()) }
    }

    @Test
    fun `rejects access to paths outside the upload directory`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()
        val outside = tempDir.resolve("outside.txt")
        outside.toFile().writeText("secret")

        assertThrows<SecurityException> { runBlocking { service.getFile(outside.toString()) } }
        assertThrows<SecurityException> { runBlocking { service.deleteFile(outside.toString()) } }
        assertThrows<SecurityException> { runBlocking { service.fileExists(outside.toString()) } }
        assertTrue(outside.exists())
    }

    @Test
    fun `rejects access to the reserved quota lock path`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()
        service.saveFile(byteArrayOf(1), "seed.yaml")
        val lockPath = tempDir.resolve("uploads").resolve(".quota.lock")

        assertThrows<SecurityException> { runBlocking { service.deleteFile(lockPath.toString()) } }
        assertTrue(lockPath.exists())
    }

    @Test
    fun `bounded reads reject before retaining bytes beyond caller limit`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 1024)
        service.init()
        val path = service.saveFile(ByteArray(33), "input.yaml")

        assertThrows<ResponseStatusException> {
            runBlocking { service.getFile(path, maxBytes = 32) }
        }
    }

    @Test
    fun `rejects uploads when the file-count quota would be exceeded`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(
            tempDir.toString(),
            maxStorageBytes = 1024,
            maxStorageFiles = 1,
        )
        service.init()
        service.saveFile(ByteArray(0), "first.yaml")

        assertThrows<ResponseStatusException> {
            runBlocking { service.saveFile(ByteArray(0), "second.yaml") }
        }
    }

    @Test
    fun `rejects uploads when the storage quota would be exceeded`(@TempDir tempDir: Path) = runBlocking {
        val service = FileSystemUploadsService(tempDir.toString(), maxStorageBytes = 4)
        service.init()
        service.saveFile(ByteArray(4), "first.yaml")

        assertThrows<ResponseStatusException> {
            runBlocking { service.saveFile(byteArrayOf(1), "second.yaml") }
        }
    }
}
