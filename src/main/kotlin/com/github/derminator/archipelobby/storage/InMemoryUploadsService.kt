package com.github.derminator.archipelobby.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
@Profile("!prod")
class InMemoryUploadsService(
    @Value($$"${archipelobby.resources.max-storage-bytes:2147483648}")
    private val maxStorageBytes: Long = 2L * 1024 * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-stored-file-bytes:67108864}")
    private val maxStoredFileBytes: Long = 64L * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-storage-files:10000}")
    private val maxStorageFiles: Int = 10_000,
) : UploadsService {

    private val storage = ConcurrentHashMap<String, ByteArray>()
    private val storageLock = Any()
    private var usedBytes = 0L

    init {
        require(maxStorageBytes > 0)
        require(maxStoredFileBytes > 0)
        require(maxStorageFiles > 0)
    }

    override suspend fun saveFile(content: ByteArray, filename: String): String {
        if (content.size.toLong() > maxStoredFileBytes) {
            throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Stored file exceeds $maxStoredFileBytes bytes")
        }
        synchronized(storageLock) {
            if (storage.size >= maxStorageFiles) {
                throw ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Upload file-count quota exceeded")
            }
            if (content.size.toLong() > maxStorageBytes - usedBytes) {
                throw ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Upload storage quota exceeded")
            }
            val key = UUID.randomUUID().toString()
            storage[key] = content.copyOf()
            usedBytes += content.size
            return key
        }
    }

    override suspend fun getFile(filePath: String): ByteArray =
        storage[filePath]?.copyOf() ?: throw NoSuchFileException(java.io.File(filePath))

    override suspend fun getFile(filePath: String, maxBytes: Long): ByteArray {
        require(maxBytes > 0)
        val content = storage[filePath] ?: throw NoSuchFileException(java.io.File(filePath))
        if (content.size.toLong() > maxBytes) {
            throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Stored file exceeds $maxBytes bytes")
        }
        return content.copyOf()
    }

    override suspend fun deleteFile(filePath: String) {
        synchronized(storageLock) {
            storage.remove(filePath)?.let { usedBytes -= it.size }
        }
    }

    override suspend fun fileExists(filePath: String): Boolean = storage.containsKey(filePath)
}
