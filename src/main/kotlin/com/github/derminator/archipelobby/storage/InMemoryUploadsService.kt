package com.github.derminator.archipelobby.storage

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
@Profile("!prod")
class InMemoryUploadsService : UploadsService {

    private val storage = ConcurrentHashMap<String, ByteArray>()

    override suspend fun saveFile(content: ByteArray, filename: String): String {
        val key = "${System.currentTimeMillis()}_$filename"
        storage[key] = content
        return key
    }

    override suspend fun getFile(filePath: String): ByteArray {
        return storage[filePath] ?: throw NoSuchFileException(java.io.File(filePath))
    }

    override suspend fun deleteFile(filePath: String) {
        storage.remove(filePath)
    }

    override suspend fun fileExists(filePath: String): Boolean {
        return storage.containsKey(filePath)
    }
}
