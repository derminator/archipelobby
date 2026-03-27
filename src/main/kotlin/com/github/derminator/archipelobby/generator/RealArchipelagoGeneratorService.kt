package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

@Service
@Profile("prod")
class RealArchipelagoGeneratorService(
    @Value($$"${archipelobby.archipelago.script-path:Archipelago/Generate.py}") private val scriptPath: String,
) : ArchipelagoGeneratorService {

    override suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val workDir = Files.createTempDirectory("archipelago-generate-")
        try {
            val playersDir = workDir.resolve("Players").also { Files.createDirectories(it) }
            val worldsDir = workDir.resolve("custom_worlds").also { Files.createDirectories(it) }
            val outputDir = workDir.resolve("output").also { Files.createDirectories(it) }

            for ((name, bytes) in yamlFiles) {
                Files.write(playersDir.resolve(name), bytes)
            }
            for ((name, bytes) in apWorldFiles) {
                Files.write(worldsDir.resolve(name), bytes)
            }

            val process = ProcessBuilder(
                "python3", scriptPath,
                "--player_files_path", playersDir.toString(),
                "--outputpath", outputDir.toString(),
                "--world_directory", worldsDir.toString(),
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Archipelago generation failed (exit $exitCode): $output",
                )
            }

            val generatedFile = findGeneratedFile(outputDir)
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Archipelago generation produced no output file",
                )
            Files.readAllBytes(generatedFile)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun findGeneratedFile(outputDir: Path): Path? =
        Files.list(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.findFirst().orElse(null)
        }
}
