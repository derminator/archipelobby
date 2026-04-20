package com.github.derminator.archipelobby.generator

import org.jetbrains.annotations.Blocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

@Component
class PythonScriptRunner(
    @Value($$"${archipelobby.python.executable:python}") private val pythonExecutable: String = "python",
    @Value($$"${archipelobby.generator.timeout-minutes:10}") private val timeoutMinutes: Long = 10,
) {

    private val logger = LoggerFactory.getLogger(PythonScriptRunner::class.java)

    /**
     * Executes a Python script with the given arguments as a CPython subprocess.
     * Stdout and stderr are merged and streamed to SLF4J in real time; the full
     * captured output is returned on success or embedded in the thrown
     * ResponseStatusException on a non-zero exit.
     */
    @Blocking
    fun run(scriptPath: String, vararg args: String): String {
        val scriptFile = File(scriptPath).absoluteFile
        val command = mutableListOf(pythonExecutable)
        command.add(scriptFile.path)
        command.addAll(args)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also {
                it.environment()["PYTHONUNBUFFERED"] = "1"
                it.environment()["DISPLAY"] = ""
            }
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

        val exited = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!exited) {
            process.destroyForcibly()
            throw ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT,
                "Python script timed out after $timeoutMinutes minutes (possible file dialog or hang)",
            )
        }
        val exitCode = process.exitValue()
        val captured = output.toString()
        if (exitCode != 0) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Python script failed (exit $exitCode): ${captured.trim()}",
            )
        }
        return captured
    }
}
