package com.github.derminator.archipelobby.generator

import com.github.derminator.archipelobby.extractFilesFromZip
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files
import java.util.Locale

@Service
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
    @Value($$"${archipelobby.archipelago.module-update-script-path:Archipelago/ModuleUpdate.py}") private val moduleUpdateScriptPath: String,
    @Value($$"${archipelobby.archipelago.location-count-script-path:python/get_location_count.py}") private val locationCountScriptPath: String,
    private val pythonScriptRunner: PythonScriptRunner,
) : ArchipelagoGeneratorService {

    private val logger = LoggerFactory.getLogger(RealArchipelagoGeneratorService::class.java)

    @PostConstruct
    fun installDependencies() {
        pythonScriptRunner.run(moduleUpdateScriptPath, "--yes")
    }

    override suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): GeneratedGame = withContext(Dispatchers.IO) {
        val workDir = Files.createTempDirectory("archipelago-generate-").toFile()
        try {
            val scriptFile = File(scriptPath).absoluteFile
            scriptFile.parentFile.copyRecursively(workDir, overwrite = true)

            val playersDir = workDir.resolve("Players").also { it.mkdirs() }
            val customWorldsDir = workDir.resolve("custom_worlds").also { it.mkdirs() }
            val outputDir = workDir.resolve("output").also { it.mkdirs() }

            for ((name, bytes) in yamlFiles) {
                playersDir.resolve(name).writeBytes(bytes)
            }
            writeApWorlds(customWorldsDir, apWorldFiles)

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

            val gameZip = Files.list(outputDir.toPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".zip") }
                    .findFirst().orElse(null)
            } ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Archipelago generation produced no game zip",
            )

            val (archipelagoBytes, walkthroughBytes, patchFiles) = extractFilesFromZip(Files.readAllBytes(gameZip))
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
            cleanupWorkDir(workDir)
        }
    }

    override suspend fun getLocationCount(yamlContent: ByteArray, apWorldContents: Map<String, ByteArray>): Int =
        withContext(Dispatchers.IO) {
            val workDir = Files.createTempDirectory("archipelago-loc-count-").toFile()
            try {
                val archipelagoDir = File(scriptPath).absoluteFile.parentFile
                archipelagoDir.copyRecursively(workDir, overwrite = true)

                val yamlFile = workDir.resolve("player.yaml").also { it.writeBytes(yamlContent) }
                val customWorldsDir = workDir.resolve("custom_worlds").also { it.mkdirs() }
                writeApWorlds(customWorldsDir, apWorldContents)

                val locationCountScript = File(locationCountScriptPath).absoluteFile
                val scriptInWorkDir = workDir.resolve(locationCountScript.name)
                locationCountScript.copyTo(scriptInWorkDir, overwrite = true)
                val output = pythonScriptRunner.run(
                    scriptInWorkDir.absolutePath,
                    workDir.absolutePath,
                    yamlFile.absolutePath,
                )
                parseLocationCount(output)
                    ?: throw ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Location count script produced unexpected output: ${output.trim()}",
                    )
            } finally {
                cleanupWorkDir(workDir)
            }
        }

    private fun writeApWorlds(customWorldsDir: File, apWorldContents: Map<String, ByteArray>) {
        val customWorldsPath = customWorldsDir.toPath()
        val seenNames = mutableSetOf<String>()
        for ((name, bytes) in apWorldContents) {
            if (!isSafeApWorldFilename(name) || !seenNames.add(name.lowercase(Locale.ROOT))) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "APWorld filename must be a unique direct .apworld child of custom_worlds",
                )
            }
            val target = customWorldsPath.resolve(name).normalize()
            if (target.parent != customWorldsPath) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "APWorld filename must be a unique direct .apworld child of custom_worlds",
                )
            }
            Files.write(target, bytes)
        }
    }

    private fun isSafeApWorldFilename(name: String): Boolean {
        if (name.isBlank() || !name.endsWith(".apworld")) return false
        if (name.any { it in "<>:\"/\\|?*" || it.isISOControl() }) return false

        val baseName = name.substringBeforeLast('.').uppercase(Locale.ROOT)
        return baseName !in WINDOWS_RESERVED_FILENAMES
    }

    private fun cleanupWorkDir(workDir: File) {
        if (!workDir.deleteRecursively()) {
            logger.warn("Failed to delete Archipelago temporary work directory: {}", workDir)
        }
    }

    private companion object {
        val WINDOWS_RESERVED_FILENAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }
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
