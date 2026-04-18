package com.github.derminator.archipelobby.generator

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.python.embedding.GraalPyResources
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

@Component
class PythonScriptRunner {

    private val logger = LoggerFactory.getLogger(PythonScriptRunner::class.java)

    /**
     * Executes a Python script with the given arguments in an isolated GraalPy context.
     * Stdout and stderr are captured and returned (or included in the exception message on failure).
     * A fresh context is created per call, ensuring thread safety for concurrent invocations.
     */
    fun run(scriptPath: String, vararg args: String): String {
        val scriptFile = File(scriptPath).absoluteFile
        val scriptDirectory = scriptFile.parent
        val outputStream = LoggingStream()
        GraalPyResources.contextBuilder()
            .allowAllAccess(true)
            .out(outputStream)
            .err(outputStream)
            .arguments("python", arrayOf(scriptFile.path, *args))
            .build()
            .use { context ->
                try {
                    context.getBindings("python").putMember("__archipelobby_script_directory__", scriptDirectory)
                    context.eval(
                        "python",
                        """
                        import sys
                        script_directory = __archipelobby_script_directory__
                        if script_directory and script_directory not in sys.path:
                            sys.path.insert(0, script_directory)
                        """.trimIndent(),
                    )

                    val source = Source.newBuilder("python", scriptFile).build()
                    context.eval(source)
                } catch (e: PolyglotException) {
                    val output = outputStream.getOutput()
                    if (!e.isExit || e.exitStatus != 0) {
                        val detail = buildString {
                            if (!e.isExit) e.message?.let { append(it).append("\n") }
                            if (output.isNotEmpty()) append(output)
                        }.trim()
                        throw ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Python script failed (exit ${if (e.isExit) e.exitStatus else "n/a"}): $detail",
                        )
                    }
                }
            }
        return outputStream.getOutput()
    }

    private inner class LoggingStream : OutputStream() {
        private val lineBuffer = StringBuilder()
        private val fullOutput = ByteArrayOutputStream()

        override fun write(b: Int) {
            fullOutput.write(b)
            val ch = b.toChar()
            if (ch == '\n') {
                writeLogLine()
            } else {
                lineBuffer.append(ch)
            }
        }

        override fun close() {
            if (lineBuffer.isNotEmpty()) {
                writeLogLine()
            }
            super.close()
        }

        private fun writeLogLine() {
            logger.info("[python] {}", lineBuffer.toString())
            lineBuffer.clear()
        }

        fun getOutput(): String = fullOutput.toString(Charsets.UTF_8)
    }
}
