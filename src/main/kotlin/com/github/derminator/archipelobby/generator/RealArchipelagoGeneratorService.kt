package com.github.derminator.archipelobby.generator

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@Service
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
    @Value($$"${archipelobby.archipelago.module-update-script-path:Archipelago/ModuleUpdate.py}") private val moduleUpdateScriptPath: String,
    private val pythonScriptRunner: PythonScriptRunner,
) : ArchipelagoGeneratorService {

    private val generationMutex = Mutex()

    @PostConstruct
    fun installDependencies() {
        pythonScriptRunner.run(moduleUpdateScriptPath, "--yes")
    }

    override suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): ByteArray = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val workDir = Files.createTempDirectory("archipelago-generate-")
            val customWorldsDir = File(scriptPath).absoluteFile.parentFile.resolve("custom_worlds").also { it.mkdirs() }
            val addedApWorlds = mutableListOf<File>()
            try {
                val playersDir = workDir.resolve("Players").also { Files.createDirectories(it) }
                val outputDir = workDir.resolve("output").also { Files.createDirectories(it) }

                for ((name, bytes) in yamlFiles) {
                    Files.write(playersDir.resolve(name), bytes)
                }
                for ((name, bytes) in apWorldFiles) {
                    val dest = customWorldsDir.resolve(name)
                    dest.writeBytes(bytes)
                    addedApWorlds.add(dest)
                }

                pythonScriptRunner.run(
                    scriptPath,
                    "--player_files_path", playersDir.toString(),
                    "--outputpath", outputDir.toString(),
                )

                val generatedFile = findGeneratedFile(outputDir)
                    ?: throw ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Archipelago generation produced no output file",
                    )
                Files.readAllBytes(generatedFile)
            } finally {
                addedApWorlds.forEach { it.delete() }
                workDir.toFile().deleteRecursively()
            }
        }
    }

    private fun findGeneratedFile(outputDir: Path): Path? =
        Files.list(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
        }
}
