package com.github.derminator.archipelobby.generator

import com.github.derminator.archipelobby.stableFileIdentity
import com.github.derminator.archipelobby.ZipResourceLimits
import com.github.derminator.archipelobby.extractFilesFromZip
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Semaphore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Service
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
    @Value($$"${archipelobby.archipelago.module-update-script-path:Archipelago/ModuleUpdate.py}") private val moduleUpdateScriptPath: String,
    @Value($$"${archipelobby.archipelago.location-count-script-path:python/get_location_count.py}") private val locationCountScriptPath: String,
    private val pythonScriptRunner: PythonScriptRunner,
    private val jobLimiter: GenerationJobLimiter,
    @Value($$"${archipelobby.resources.max-generation-input-bytes:268435456}")
    private val maxGenerationInputBytes: Long = 256L * 1024 * 1024,
    @Value($$"${archipelobby.resources.max-generated-archive-bytes:67108864}")
    private val maxGeneratedArchiveBytes: Long = 64L * 1024 * 1024,
) : ArchipelagoGeneratorService {

    @PostConstruct
    fun installDependencies() {
        require(maxGenerationInputBytes > 0)
        require(maxGeneratedArchiveBytes > 0)
        pythonScriptRunner.run(moduleUpdateScriptPath, "--yes")
    }

    override suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): GeneratedGame = withContext(Dispatchers.IO) {
        validateInputSize(yamlFiles.values + apWorldFiles.values)
        val workDir = Files.createTempDirectory("archipelago-generate-").toFile()
        try {
            val scriptFile = File(scriptPath).absoluteFile
            scriptFile.parentFile.copyRecursively(workDir, overwrite = true)

            val playersDir = workDir.resolve("Players").also { it.mkdirs() }
            val customWorldsDir = workDir.resolve("custom_worlds").also { it.mkdirs() }
            val outputDir = workDir.resolve("output").also { it.mkdirs() }

            writeGeneratedJobFiles(playersDir.toPath(), yamlFiles, ".yaml")
            writeGeneratedJobFiles(customWorldsDir.toPath(), apWorldFiles, ".apworld")

            // Install any dependencies introduced by the current set of APWorlds.
            val moduleUpdateFile = File(moduleUpdateScriptPath).absoluteFile
            pythonScriptRunner.run(
                workDir.resolve(moduleUpdateFile.name).path,
                "--yes",
            )

            pythonScriptRunner.run(
                workDir.resolve(scriptFile.name).path,
                "--player_files_path", playersDir.path,
                "--outputpath", outputDir.path,
            )

            // The .archipelago multidata, the spoiler log, and the per-slot patch files all
            // live inside the zip.
            val generatedZipBytes = readGeneratedArchiveFromDirectory(outputDir.toPath(), maxGeneratedArchiveBytes)
            val (archipelagoBytes, walkthroughBytes, patchFiles) = extractFilesFromZip(
                generatedZipBytes,
                ZipResourceLimits(maxCompressedBytes = maxGeneratedArchiveBytes),
            )
            val archipelago = archipelagoBytes
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Game zip produced by Archipelago contains no .archipelago file",
                )
            val walkthrough = walkthroughBytes
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Game zip produced by Archipelago contains no walkthrough file",
                )

            GeneratedGame(archipelago, walkthrough, patchFiles)
        } finally {
            workDir.deleteRecursively()
        }
    }

    override suspend fun getLocationCount(yamlContent: ByteArray, apWorldContents: Map<String, ByteArray>): Int =
        jobLimiter.run {
            withContext(Dispatchers.IO) {
                validateInputSize(listOf(yamlContent) + apWorldContents.values)
                val tempDir = Files.createTempDirectory("archipelago-loc-count-").toFile()
                try {
                    val yamlFile = tempDir.resolve("player.yaml").also { it.writeBytes(yamlContent) }
                    val apWorldPaths = writeGeneratedJobFiles(
                        tempDir.toPath(),
                        apWorldContents,
                        ".apworld",
                    ).map { it.toAbsolutePath().toString() }
                    val archipelagoDir = File(scriptPath).absoluteFile.parent
                    val args = (listOf(archipelagoDir, yamlFile.absolutePath) + apWorldPaths).toTypedArray()
                    val output = pythonScriptRunner.run(File(locationCountScriptPath).absoluteFile.path, *args)
                    parseLocationCount(output)
                        ?: throw ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Location count script produced unexpected output: ${output.trim()}",
                        )
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }

    private fun validateInputSize(inputs: Collection<ByteArray>) {
        var total = 0L
        inputs.forEach { input ->
            if (input.size.toLong() > maxGenerationInputBytes - total) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Generation input exceeds $maxGenerationInputBytes bytes",
                )
            }
            total += input.size
        }
    }

}

internal fun validateGeneratedOutputDirectory(path: Path): Path {
    val lexical = path.toAbsolutePath().normalize()
    if (Files.isSymbolicLink(lexical) || !Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS)) {
        throw SecurityException("Generated output directory must be a regular non-symbolic-link directory")
    }
    if (lexical.toRealPath() != lexical) {
        throw SecurityException("Generated output directory escapes its lexical path")
    }
    return lexical
}

internal fun readGeneratedArchiveFromDirectory(directory: Path, maxBytes: Long): ByteArray {
    val validatedDirectory = validateGeneratedOutputDirectory(directory)
    val expectedKey = stableFileIdentity(validatedDirectory)
    Files.newDirectoryStream(validatedDirectory).use { stream ->
        val openedKey = stableFileIdentity(validatedDirectory)
        if (openedKey != expectedKey) {
            throw SecurityException("Generated output directory changed while opening it")
        }
        var candidate: Path? = null
        var scanned = 0
        for (path in stream) {
            scanned++
            if (scanned > 256) {
                throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Generated output contains too many files")
            }
            if (path.fileName.toString().endsWith(".zip") &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)
            ) {
                if (candidate != null) {
                    throw ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Archipelago generation produced multiple game zips",
                    )
                }
                candidate = path.fileName
            }
        }
        val archiveName = candidate ?: throw ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Archipelago generation produced no game zip",
        )
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        if (stream is SecureDirectoryStream<Path>) {
            val attributes = stream.getFileAttributeView(
                archiveName,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).readAttributes()
            if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                throw SecurityException("Generated game archive must be a regular non-symbolic-link file")
            }
            stream.newByteChannel(archiveName, options).use { channel ->
                return readGeneratedArchiveChannel(channel, maxBytes)
            }
        }

        // SecureDirectoryStream is not available on every provider (notably Windows). In that
        // fallback, pin the opened file handle between two directory-identity checks. The Python
        // process tree has already been terminated/joined, so no untrusted worker remains to race it.
        val beforeKey = stableFileIdentity(validatedDirectory)
        if (beforeKey != expectedKey) {
            throw SecurityException("Generated output directory changed before opening the archive")
        }
        val archivePath = validatedDirectory.resolve(archiveName).normalize()
        if (archivePath.parent != validatedDirectory ||
            archivePath.toRealPath(LinkOption.NOFOLLOW_LINKS).parent != validatedDirectory
        ) {
            throw SecurityException("Generated game archive escapes its output directory")
        }
        Files.newByteChannel(archivePath, options).use { channel ->
            val afterDirectory = validateGeneratedOutputDirectory(validatedDirectory)
            val afterKey = stableFileIdentity(afterDirectory)
            if (beforeKey != afterKey) {
                throw SecurityException("Generated output directory changed while opening the archive")
            }
            return readGeneratedArchiveChannel(channel, maxBytes)
        }
    }
}

internal fun readGeneratedArchive(path: Path, maxBytes: Long): ByteArray {
    require(maxBytes > 0)
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw SecurityException("Generated game archive must be a regular non-symbolic-link file")
    }
    val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    Files.newByteChannel(path, options).use { channel ->
        return readGeneratedArchiveChannel(channel, maxBytes)
    }
}

private fun readGeneratedArchiveChannel(channel: SeekableByteChannel, maxBytes: Long): ByteArray {
    require(maxBytes > 0)
    val declaredSize = channel.size()
    if (declaredSize > maxBytes) {
        throw ResponseStatusException(
            HttpStatus.CONTENT_TOO_LARGE,
            "Generated game archive exceeds $maxBytes bytes",
        )
    }
    val output = ByteArrayOutputStream(minOf(declaredSize, 64L * 1024).toInt())
    Channels.newInputStream(channel).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > maxBytes - total) {
                throw ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Generated game archive exceeds $maxBytes bytes",
                )
            }
            output.write(buffer, 0, count)
            total += count
        }
    }
    return output.toByteArray()
}

@Component
class GenerationJobLimiter(
    @Value($$"${archipelobby.resources.max-concurrent-generation-jobs:2}") maxConcurrentJobs: Int = 2,
) {
    private val permits = Semaphore(maxConcurrentJobs.also { require(it > 0) })

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[PermitContext]?.limiter === this) return block()
        if (!permits.tryAcquire()) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many Archipelago jobs are already running",
            )
        }
        return try {
            withContext(PermitContext(this)) { block() }
        } finally {
            permits.release()
        }
    }

    private class PermitContext(val limiter: GenerationJobLimiter) :
        AbstractCoroutineContextElement(PermitContext) {
        companion object Key : CoroutineContext.Key<PermitContext>
    }
}

/** Writes map values under generated names; untrusted map keys never become paths. */
internal fun writeGeneratedJobFiles(
    targetDirectory: Path,
    files: Map<String, ByteArray>,
    extension: String,
): List<Path> {
    require(extension.matches(Regex("\\.[A-Za-z0-9]{1,16}"))) { "Unsafe generated file extension" }
    val root = Files.createDirectories(targetDirectory).toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(root)) { "Generated job directory must not be a symbolic link" }
    require(root.toRealPath() == root) { "Generated job directory escapes its lexical path" }
    return files.values.mapIndexed { index, bytes ->
        val target = root.resolve("job-${index + 1}$extension").normalize()
        check(target.startsWith(root))
        Files.write(
            target,
            bytes,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
}

/**
 * The Python runner merges stderr into stdout, so world-generation warnings can
 * precede the count. The location-count script always prints its result last.
 */
internal fun parseLocationCount(output: String): Int? =
    output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.isNotEmpty() }
        ?.toIntOrNull()
