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
    ): ByteArray = withContext(Dispatchers.IO) {
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

            pythonScriptRunner.run(
                workDir.resolve(scriptFile.name).path,
                "--player_files_path", playersDir.path,
                "--outputpath", outputDir.path,
            )

            val generatedFile = findGeneratedFile(outputDir.toPath())
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Archipelago generation produced no output file",
                )
            Files.readAllBytes(generatedFile)
        } finally {
            workDir.deleteRecursively()
        }
    }

    override suspend fun getLocationCount(yamlFilePath: String, apWorldFilePaths: List<String>): Int? =
        withContext(Dispatchers.IO) {
            try {
                val archipelagoDir = File(scriptPath).absoluteFile.parent
                val args = (listOf(archipelagoDir, yamlFilePath) + apWorldFilePaths).toTypedArray()
                val output = pythonScriptRunner.run(File(locationCountScriptPath).absoluteFile.path, *args)
                output.trim().toIntOrNull()
            } catch (e: Exception) {
                null
            }
        }

    private fun findGeneratedFile(outputDir: Path): Path? =
        Files.list(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
        }
}
