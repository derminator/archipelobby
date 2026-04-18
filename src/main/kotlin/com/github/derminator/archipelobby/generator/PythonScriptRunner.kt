package com.github.derminator.archipelobby.generator

import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.python.embedding.GraalPyResources
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.File

@Component
class PythonScriptRunner {

    /**
     * Executes a Python script with the given arguments in an isolated GraalPy context.
     * Stdout and stderr are captured and returned (or included in the exception message on failure).
     * A fresh context is created per call, ensuring thread safety for concurrent invocations.
     */
    fun run(scriptPath: String, vararg args: String): String {
        val outputStream = ByteArrayOutputStream()
        GraalPyResources.contextBuilder()
            .allowAllAccess(true)
            .out(outputStream)
            .err(outputStream)
            .arguments("python", arrayOf(scriptPath, *args))
            .build()
            .use { context ->
                try {
                    val source = Source.newBuilder("python", File(scriptPath)).build()
                    context.eval(source)
                } catch (e: PolyglotException) {
                    val output = outputStream.toString(Charsets.UTF_8)
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
        return outputStream.toString(Charsets.UTF_8)
    }
}
