package com.github.derminator.archipelobby.storage

import com.github.derminator.archipelobby.stableFileIdentity
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

@Service
@Profile("prod")
class FileSystemUploadsService(
    @Value($$"${app.data-dir}") private val dataDir: String,
    @Value($$"${archipelobby.resources.max-storage-bytes:2147483648}")
    private val maxStorageBytes: Long = 2L * 1024 * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-stored-file-bytes:67108864}")
    private val maxStoredFileBytes: Long = 64L * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-storage-files:10000}")
    private val maxStorageFiles: Int = 10_000,
) : UploadsService {

    private val uploadsDir: Path by lazy { Paths.get(dataDir, "uploads").toAbsolutePath().normalize() }
    private var uploadsRootFileKey: Any? = null
    private var initialized = false

    @PostConstruct
    fun init() {
        require(maxStorageBytes > 0)
        require(maxStoredFileBytes > 0)
        require(maxStorageFiles > 0)
        Files.createDirectories(uploadsDir)
        try {
            validateUploadRoot()
        } catch (error: SecurityException) {
            throw IllegalArgumentException(error.message, error)
        }
        uploadsRootFileKey = try {
            stableFileIdentity(uploadsDir)
        } catch (error: SecurityException) {
            throw IllegalArgumentException(error.message, error)
        }
        initialized = true
    }

    override suspend fun saveFile(content: ByteArray, filename: String): String = withContext(Dispatchers.IO) {
        // The client filename is metadata only. A generated storage key makes path
        // traversal and filename collisions impossible at this boundary.
        if (content.size.toLong() > maxStoredFileBytes) {
            throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Stored file exceeds $maxStoredFileBytes bytes")
        }
        synchronized(GLOBAL_STORAGE_LOCK) {
            withStorageQuotaLock {
                val usedBytes = currentStorageBytes()
                if (content.size.toLong() > maxStorageBytes - usedBytes) {
                    throw ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Upload storage quota exceeded")
                }
                val filePath = uploadsDir.resolve(UUID.randomUUID().toString()).normalize()
                check(filePath.parent == uploadsDir)
                try {
                    Files.write(
                        filePath,
                        content,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (error: Exception) {
                    Files.deleteIfExists(filePath)
                    throw error
                }
                filePath.toString()
            }
        }
    }

    override suspend fun getFile(filePath: String): ByteArray = withContext(Dispatchers.IO) {
        val path = containedPath(filePath)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw NoSuchFileException(path.toFile())
        }
        readBounded(path, maxStoredFileBytes)
    }

    override suspend fun getFile(filePath: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        val path = containedPath(filePath)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw NoSuchFileException(path.toFile())
        }
        readBounded(path, minOf(maxStoredFileBytes, maxBytes))
    }

    override suspend fun deleteFile(filePath: String) = withContext(Dispatchers.IO) {
        val path = containedPath(filePath)
        synchronized(GLOBAL_STORAGE_LOCK) {
            withStorageQuotaLock { Files.deleteIfExists(path) }
        }
        Unit
    }

    override suspend fun fileExists(filePath: String): Boolean = withContext(Dispatchers.IO) {
        Files.isRegularFile(containedPath(filePath), LinkOption.NOFOLLOW_LINKS)
    }

    private fun containedPath(filePath: String): Path {
        validateUploadRoot()
        val lexicalPath = Paths.get(filePath).toAbsolutePath().normalize()
        if (!lexicalPath.startsWith(uploadsDir)) {
            throw SecurityException("Upload path is outside the configured upload directory")
        }
        if (lexicalPath.parent != uploadsDir) {
            throw SecurityException("Stored files must be direct children of the upload directory")
        }
        if (lexicalPath == uploadsDir.resolve(QUOTA_LOCK_FILE)) {
            throw SecurityException("Upload quota lock is not a stored file")
        }
        if (!Files.exists(lexicalPath, LinkOption.NOFOLLOW_LINKS)) return lexicalPath
        if (Files.isSymbolicLink(lexicalPath)) {
            throw SecurityException("Symbolic links are not valid upload paths")
        }
        val realRoot = uploadsDir.toRealPath()
        val realPath = lexicalPath.toRealPath()
        if (!realPath.startsWith(realRoot) || realPath.parent != realRoot) {
            throw SecurityException("Upload path escapes the configured upload directory")
        }
        return realPath
    }

    private fun readBounded(path: Path, maxBytes: Long): ByteArray =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val declaredSize = channel.size()
            if (declaredSize > maxBytes || declaredSize > Int.MAX_VALUE) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Stored file exceeds $maxBytes bytes",
                )
            }
            val output = ByteArrayOutputStream(declaredSize.toInt())
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > maxBytes - total) {
                        throw ResponseStatusException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "Stored file exceeds $maxBytes bytes",
                        )
                    }
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            output.toByteArray()
        }

    private fun currentStorageBytes(): Long {
        var total = 0L
        var fileCount = 0
        Files.list(uploadsDir).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path.fileName.toString() == QUOTA_LOCK_FILE ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ) {
                    continue
                }
                val size = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.size() }
                fileCount++
                if (fileCount >= maxStorageFiles) {
                    throw ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Upload file-count quota exceeded")
                }
                if (size > maxStorageBytes - total) return maxStorageBytes
                total += size
            }
        }
        return total
    }

    private fun <T> withStorageQuotaLock(block: () -> T): T {
        validateUploadRoot()
        val lockPath = uploadsDir.resolve(QUOTA_LOCK_FILE)
        if (Files.isSymbolicLink(lockPath)) {
            throw SecurityException("Upload quota lock must not be a symbolic link")
        }
        return FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.lock().use {
                validateUploadRoot()
                block()
            }
        }
    }

    private fun validateUploadRoot() {
        if (Files.isSymbolicLink(uploadsDir)) {
            throw SecurityException("Upload directory must not be a symbolic link")
        }
        if (uploadsDir.toRealPath() != uploadsDir) {
            throw SecurityException("Upload directory escapes its configured path")
        }
        if (initialized) {
            val expectedKey = uploadsRootFileKey
                ?: throw SecurityException("Upload directory identity is unavailable")
            val currentKey = stableFileIdentity(uploadsDir)
            if (currentKey != expectedKey) {
                throw SecurityException("Upload directory was replaced after initialization")
            }
        }
    }

    private companion object {
        const val QUOTA_LOCK_FILE = ".quota.lock"
        val GLOBAL_STORAGE_LOCK = Any()
    }
}
