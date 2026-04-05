package com.github.derminator.archipelobby.generator

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.File

@Component
class PythonScriptRunner {

    /**
     * Executes a Python script with the given arguments in an isolated GraalPy context.
     * stdout and stderr are captured and returned (or included in the exception message on failure).
     * A fresh context is created per call, ensuring thread safety for concurrent invocations.
     */
    fun run(scriptPath: String, vararg args: String): String {
        val outputStream = ByteArrayOutputStream()
        Context.newBuilder("python")
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
                        throw ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Python script failed (exit ${if (e.isExit) e.exitStatus else "n/a"}): $output",
                        )
                    }
                }
            }
        return outputStream.toString(Charsets.UTF_8)
    }
}
