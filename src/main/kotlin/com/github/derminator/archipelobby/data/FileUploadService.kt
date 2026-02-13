package com.github.derminator.archipelobby.data

import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service
class FileUploadService(
    private val yamlUploadRepository: YamlUploadRepository,
    private val apWorldRepository: ApWorldRepository
) {
    private val uploadBasePath = Paths.get("uploads")

    init {
        Files.createDirectories(uploadBasePath)
    }

    fun uploadYaml(roomId: Long, userId: Long, filePart: FilePart): Mono<YamlUpload> = mono {
        val fileName = filePart.filename()
        if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a YAML file")
        }

        // Delete existing upload if present
        yamlUploadRepository.deleteByRoomIdAndUserId(roomId, userId).subscribe()

        val roomPath = uploadBasePath.resolve("room_$roomId").resolve("yamls")
        Files.createDirectories(roomPath)

        val filePath = roomPath.resolve("user_${userId}_$fileName")
        DataBufferUtils.write(filePart.content(), filePath).then().block()

        val fileSize = Files.size(filePath)

        // Try to extract game name from YAML content
        val gameName = extractGameNameFromYaml(filePath)

        val yamlUpload = YamlUpload(
            roomId = roomId,
            userId = userId,
            gameName = gameName,
            fileName = fileName,
            filePath = filePath.toString(),
            fileSize = fileSize
        )

        yamlUploadRepository.save(yamlUpload).block() ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    fun uploadApWorld(roomId: Long, userId: Long, filePart: FilePart): Mono<ApWorld> = mono {
        val fileName = filePart.filename()
        if (!fileName.endsWith(".apworld")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an .apworld file")
        }

        val worldName = fileName.removeSuffix(".apworld")

        val roomPath = uploadBasePath.resolve("room_$roomId").resolve("apworlds")
        Files.createDirectories(roomPath)

        val filePath = roomPath.resolve(fileName)
        DataBufferUtils.write(filePart.content(), filePath).then().block()

        val fileSize = Files.size(filePath)

        val apWorld = ApWorld(
            roomId = roomId,
            uploadedBy = userId,
            worldName = worldName,
            fileName = fileName,
            filePath = filePath.toString(),
            fileSize = fileSize
        )

        apWorldRepository.save(apWorld).block() ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun extractGameNameFromYaml(filePath: Path): String {
        return try {
            val content = Files.readString(filePath)
            // Look for "game:" or "name:" field in YAML
            val gamePattern = Regex("""(?:game|name):\s*([^\n]+)""", RegexOption.IGNORE_CASE)
            val match = gamePattern.find(content)
            match?.groupValues?.get(1)?.trim() ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun deleteYaml(roomId: Long, userId: Long): Mono<Void> = mono {
        val yaml = yamlUploadRepository.findByRoomIdAndUserId(roomId, userId).block()
        yaml?.let {
            val file = File(it.filePath)
            if (file.exists()) {
                file.delete()
            }
            yamlUploadRepository.deleteById(it.id!!).block()
        }
    }

    fun deleteApWorld(apWorldId: Long): Mono<Void> = mono {
        val apWorld = apWorldRepository.findById(apWorldId).block()
        apWorld?.let {
            val file = File(it.filePath)
            if (file.exists()) {
                file.delete()
            }
            apWorldRepository.deleteById(it.id!!).block()
        }
    }
}