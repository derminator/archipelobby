package com.github.derminator.archipelobby.generator

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files

@Component
class PythonScriptRunner(
    @Value($$"${archipelobby.python.executable:python}") private val pythonExecutable: String = "python",
) {

    private val logger = LoggerFactory.getLogger(PythonScriptRunner::class.java)

    /**
     * Executes a Python script with the given arguments as a CPython subprocess.
     * Stdout and stderr are merged and streamed to SLF4J in real time; the full
     * captured output is returned on success, or embedded in the thrown
     * ResponseStatusException on a non-zero exit.
     */
    fun run(scriptPath: String, vararg args: String, preamble: String = ""): String {
        val scriptFile = File(scriptPath).absoluteFile
        val wrapperFile = if (preamble.isNotBlank()) writeWrapper(scriptFile, args, preamble) else null
        try {
            val command = mutableListOf(pythonExecutable)
            if (wrapperFile != null) {
                command.add(wrapperFile.absolutePath)
            } else {
                command.add(scriptFile.path)
                command.addAll(args)
            }

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { it.environment()["PYTHONUNBUFFERED"] = "1" }
                .start()
            process.outputStream.close()

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    logger.info("[python] {}", line)
                    output.append(line).append('\n')
                }
            }

            val exitCode = process.waitFor()
            val captured = output.toString()
            if (exitCode != 0) {
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Python script failed (exit $exitCode): ${captured.trim()}",
                )
            }
            return captured
        } finally {
            wrapperFile?.delete()
        }
    }

    private fun writeWrapper(scriptFile: File, args: Array<out String>, preamble: String): File {
        val wrapper = Files.createTempFile("archipelobby-py-wrapper-", ".py").toFile()
        val argvList = (listOf(scriptFile.path) + args).joinToString(", ") { pythonLiteral(it) }
        val scriptLit = pythonLiteral(scriptFile.path)
        wrapper.writeText(
            buildString {
                append("import sys, os\n")
                append("__ab_script = ").append(scriptLit).append('\n')
                append("__ab_dir = os.path.dirname(os.path.abspath(__ab_script))\n")
                append("if __ab_dir and __ab_dir not in sys.path:\n")
                append("    sys.path.insert(0, __ab_dir)\n")
                append("sys.argv = [").append(argvList).append("]\n")
                append(preamble).append('\n')
                append("with open(__ab_script, 'rb') as __ab_f:\n")
                append("    __ab_code = compile(__ab_f.read(), __ab_script, 'exec')\n")
                append("__file__ = __ab_script\n")
                append("exec(__ab_code)\n")
            },
            Charsets.UTF_8,
        )
        return wrapper
    }

    private fun pythonLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }
}
