package com.github.derminator.archipelobby.generator

import com.github.derminator.archipelobby.extractFilesFromZip
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files

@Service
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
    @Value($$"${archipelobby.archipelago.module-update-script-path:Archipelago/ModuleUpdate.py}") private val moduleUpdateScriptPath: String,
    @Value($$"${archipelobby.archipelago.location-count-script-path:python/get_location_count.py}") private val locationCountScriptPath: String,
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

            // Both the .archipelago multidata and the spoiler log live inside the zip.
            val (archipelagoBytes, walkthroughBytes) = extractFilesFromZip(Files.readAllBytes(gameZip))
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

            GeneratedGame(archipelago, walkthrough)
        } finally {
            workDir.deleteRecursively()
        }
    }

    override suspend fun getLocationCount(yamlContent: ByteArray, apWorldContents: Map<String, ByteArray>): Int =
        withContext(Dispatchers.IO) {
            val tempDir = Files.createTempDirectory("archipelago-loc-count-").toFile()
            try {
                val yamlFile = tempDir.resolve("player.yaml").also { it.writeBytes(yamlContent) }
                val apWorldPaths = apWorldContents.map { (name, bytes) ->
                    tempDir.resolve(name).also { it.writeBytes(bytes) }.absolutePath
                }
                val archipelagoDir = File(scriptPath).absoluteFile.parent
                val args = (listOf(archipelagoDir, yamlFile.absolutePath) + apWorldPaths).toTypedArray()
                val output = pythonScriptRunner.run(File(locationCountScriptPath).absoluteFile.path, *args)
                output.trim().toIntOrNull()
                    ?: throw ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Location count script produced unexpected output: ${output.trim()}",
                    )
            } finally {
                tempDir.deleteRecursively()
            }
        }
}
