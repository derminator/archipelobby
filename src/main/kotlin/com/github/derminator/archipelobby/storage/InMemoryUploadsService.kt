package com.github.derminator.archipelobby.storage

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.context.annotation.Profile
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

@Service
@Profile("!prod")
class InMemoryUploadsService : UploadsService {

    private val storage = ConcurrentHashMap<String, ByteArray>()

    override suspend fun saveFile(filePart: FilePart): String {
        val fileName = "${System.currentTimeMillis()}_${filePart.filename()}"
        val outputStream = ByteArrayOutputStream()

        filePart.content().collectList().awaitSingleOrNull()?.forEach { dataBuffer ->
            val bytes = ByteArray(dataBuffer.readableByteCount())
            dataBuffer.read(bytes)
            outputStream.write(bytes)
        }

        storage[fileName] = outputStream.toByteArray()
        return fileName
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
