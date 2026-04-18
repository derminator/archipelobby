package com.github.derminator.archipelobby.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains

class PythonScriptRunnerTest {

    private val runner = PythonScriptRunner()

    @Test
    fun `error message includes Python exception details when script raises exception`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("raise RuntimeError('test error message')")

        val exception = assertThrows<ResponseStatusException> {
            runner.run(script.toString())
        }

        assertContains(exception.reason ?: "", "RuntimeError")
        assertContains(exception.reason ?: "", "test error message")
    }

    @Test
    fun `captures stdout output on successful execution`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("print('hello world')")

        val output = runner.run(script.toString())

        assertContains(output, "hello world")
    }

    @Test
    fun `non-zero exit code is included in error message`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("import sys\nsys.exit(1)")

        val exception = assertThrows<ResponseStatusException> {
            runner.run(script.toString())
        }

        assertContains(exception.reason ?: "", "exit 1")
    }

    @Test
    fun `clean exit (exit code 0) does not throw exception`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("print('done')\nimport sys\nsys.exit(0)")

        val output = runner.run(script.toString())

        assertContains(output, "done")
    }
}
