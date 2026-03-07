package com.github.derminator.archipelobby.generation

import org.graalvm.polyglot.Context
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class GenerationService {

    private val log = LoggerFactory.getLogger(GenerationService::class.java)

    /**
     * Runs the Archipelago generator via the GraalPy Truffle bridge.
     *
     * [playerFilesDir] must contain the YAML files to generate from.
     * [outputDir] will receive the generated .archipelago package.
     * [archipelagoPath] is the root of the Archipelago Python project (defaults to /archipelago).
     *
     * Returns the path to the generated .archipelago file.
     */
    fun generate(
        playerFilesDir: Path,
        outputDir: Path,
        archipelagoPath: Path = Path.of(System.getenv("ARCHIPELAGO_PATH") ?: "/archipelago")
    ): Path {
        val graalPyHome = System.getenv("GRAALPY_HOME")

        log.info("Starting Archipelago generation: playerFiles={} output={} archipelago={}", playerFilesDir, outputDir, archipelagoPath)

        val contextBuilder = Context.newBuilder("python")
            .allowAllAccess(true)
            .currentWorkingDirectory(archipelagoPath)

        if (graalPyHome != null) {
            log.debug("Configuring GraalPy home from GRAALPY_HOME: {}", graalPyHome)
            contextBuilder
                .option("python.Executable", "$graalPyHome/bin/graalpy")
                .option("python.PythonHome", graalPyHome)
        }

        contextBuilder.build().use { ctx ->
            ctx.eval(
                "python", """
import sys, os
os.chdir('$archipelagoPath')
sys.path.insert(0, '$archipelagoPath')
sys.argv = ['Generate.py', '--player_files_path', '$playerFilesDir', '--output_path', '$outputDir']
with open('$archipelagoPath/Generate.py') as _f:
    exec(compile(_f.read(), 'Generate.py', 'exec'), {'__name__': '__main__', '__file__': '$archipelagoPath/Generate.py'})
""".trimIndent()
            )
        }

        log.info("Generation complete, scanning output directory: {}", outputDir)

        return outputDir.toFile()
            .listFiles { f -> f.name.endsWith(".archipelago") }
            ?.firstOrNull()
            ?.toPath()
            ?: throw IllegalStateException("Archipelago generator produced no .archipelago file in $outputDir")
    }
}
