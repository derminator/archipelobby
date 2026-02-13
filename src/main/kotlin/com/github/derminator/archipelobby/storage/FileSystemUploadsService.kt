package com.github.derminator.archipelobby.storage

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

@Service
@Profile("prod")
class FileSystemUploadsService(
    @Value($$"${app.data-dir}") private val dataDir: String
) : UploadsService {

    private val uploadsDir by lazy { Paths.get(dataDir, "uploads") }

    @PostConstruct
    fun init() {
        Files.createDirectories(uploadsDir)
    }

    override suspend fun saveFile(filePart: FilePart): String {
        val filePath = uploadsDir.resolve("${System.currentTimeMillis()}_${filePart.filename()}")
        filePart.transferTo(filePath).awaitSingleOrNull()
        return filePath.toString()
    }

    override suspend fun getFile(filePath: String): ByteArray = withContext(Dispatchers.IO) {
        val path = Paths.get(filePath)
        if (Files.exists(path)) {
            Files.readAllBytes(path)
        } else {
            throw NoSuchFileException(path.toFile())
        }
    }

    override suspend fun deleteFile(filePath: String) = withContext(Dispatchers.IO) {
        val path = Paths.get(filePath)
        if (Files.exists(path)) {
            Files.delete(path)
        }
    }

    override suspend fun fileExists(filePath: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(Paths.get(filePath))
    }
}
