package com.github.derminator.archipelobby.generator

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

@Service
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
    @Value($$"${archipelobby.archipelago.module-update-script-path:Archipelago/ModuleUpdate.py}") private val moduleUpdateScriptPath: String,
    private val pythonScriptRunner: PythonScriptRunner,
) : ArchipelagoGeneratorService {

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
            for ((name, bytes) in apWorldFiles) {
                customWorldsDir.resolve(name).writeBytes(bytes)
            }

            // Install any dependencies introduced by the current set of APWorlds.
            val moduleUpdateFile = File(moduleUpdateScriptPath).absoluteFile
            pythonScriptRunner.run(
                workDir.resolve(moduleUpdateFile.name).path,
                "--yes",
            )

            // --spoiler 3 ensures the spoiler log is always written alongside the output zip.
            pythonScriptRunner.run(
                workDir.resolve(scriptFile.name).path,
                "--player_files_path", playersDir.path,
                "--outputpath", outputDir.path,
                "--spoiler", "3",
            )

            val outputFiles = Files.list(outputDir.toPath()).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }

            // Generate.py writes AP_<seed>.zip and AP_<seed>_Spoiler.txt directly to --outputpath.
            // The .archipelago multidata file lives inside the zip and must be extracted.
            val gameZip = outputFiles.find { it.fileName.toString().endsWith(".zip") }
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Archipelago generation produced no game zip",
                )
            val walkthroughFile = outputFiles.find { it.fileName.toString().endsWith(".txt") }
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Archipelago generation produced no walkthrough file",
                )

            val archipelagoBytes = extractArchipelagoFromZip(Files.readAllBytes(gameZip))
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Game zip produced by Archipelago contains no .archipelago file",
                )

            GeneratedGame(archipelagoBytes, Files.readAllBytes(walkthroughFile))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun extractArchipelagoFromZip(zipBytes: ByteArray): ByteArray? {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".archipelago")) {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
