package com.github.derminator.archipelobby

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

@Service
class StorageService(
    @Value("\${storage.base-path:data}") private val basePath: String
) {
    private val baseDir: Path = Path.of(basePath)

    init {
        Files.createDirectories(baseDir)
        Files.createDirectories(baseDir.resolve("worlds"))
        Files.createDirectories(baseDir.resolve("rooms"))
    }

    data class StoredFile(val name: String, val size: Long, val lastModified: Instant)

    // ---- Room YAMLs ----
    fun storeRoomYaml(roomId: Long, originalFilename: String, content: java.io.InputStream): String {
        require(originalFilename.isNotBlank()) { "Filename must not be blank" }
        val cleaned = sanitize(originalFilename)
        require(cleaned.endsWith(".yaml", true) || cleaned.endsWith(".yml", true)) { "Only .yaml or .yml allowed" }
        val dir = baseDir.resolve("rooms").resolve(roomId.toString()).resolve("yamls")
        Files.createDirectories(dir)
        val target = uniqueTarget(dir, cleaned)
        content.use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target.fileName.toString()
    }

    fun listRoomYamls(roomId: Long): List<StoredFile> {
        val dir = baseDir.resolve("rooms").resolve(roomId.toString()).resolve("yamls")
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { path ->
                    val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                    StoredFile(path.fileName.toString(), attrs.size(), attrs.lastModifiedTime().toInstant())
                }
                .sorted(Comparator.comparing<StoredFile, Instant> { it.lastModified }.reversed())
                .toList()
        }
    }

    fun loadRoomYaml(roomId: Long, filename: String): FileSystemResource {
        val sanitized = sanitize(filename)
        val file = baseDir.resolve("rooms").resolve(roomId.toString()).resolve("yamls").resolve(sanitized)
        if (!Files.exists(file)) throw IOException("File not found")
        return FileSystemResource(file)
    }

    // ---- Global APWorlds ----
    fun storeWorld(originalFilename: String, content: java.io.InputStream): String {
        require(originalFilename.isNotBlank()) { "Filename must not be blank" }
        val cleaned = sanitize(originalFilename)
        require(cleaned.endsWith(".apworld", true)) { "Only .apworld allowed" }
        val dir = baseDir.resolve("worlds")
        Files.createDirectories(dir)
        val target = uniqueTarget(dir, cleaned)
        content.use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target.fileName.toString()
    }

    fun listWorlds(): List<StoredFile> {
        val dir = baseDir.resolve("worlds")
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { path ->
                    val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                    StoredFile(path.fileName.toString(), attrs.size(), attrs.lastModifiedTime().toInstant())
                }
                .sorted(Comparator.comparing<StoredFile, Instant> { it.lastModified }.reversed())
                .toList()
        }
    }

    fun loadWorld(filename: String): FileSystemResource {
        val sanitized = sanitize(filename)
        val file = baseDir.resolve("worlds").resolve(sanitized)
        if (!Files.exists(file)) throw IOException("File not found")
        return FileSystemResource(file)
    }

    private fun uniqueTarget(dir: Path, cleanedName: String): Path {
        var target = dir.resolve(cleanedName)
        if (!Files.exists(target)) return target
        val name = cleanedName.substringBeforeLast('.')
        val ext = cleanedName.substringAfterLast('.', missingDelimiterValue = "")
        var i = 1
        while (true) {
            val candidate = if (ext.isNotEmpty())
                dir.resolve("${name} (${i}).${ext}")
            else dir.resolve("${name} (${i})")
            if (!Files.exists(candidate)) return candidate
            i++
        }
    }

    private fun sanitize(filename: String): String {
        val cleaned = StringUtils.getFilename(filename) ?: "file"
        return cleaned.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
