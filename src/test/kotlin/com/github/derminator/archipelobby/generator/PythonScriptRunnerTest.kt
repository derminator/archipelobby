package com.github.derminator.archipelobby.generator

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertTrue

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

    @Test
    fun `pip is importable inside the GraalPy context`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("check_pip.py")
        script.writeText("import pip\nprint(pip.__version__)")

        val output = runner.run(script.toString())

        assertTrue(
            output.trim().matches(Regex("""\d+\.\d+.*""")),
            "Expected a pip version in output but got: $output",
        )
    }

    @Test
    fun `output lines are logged in real-time during script execution`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("print('live output')")

        val logger = LoggerFactory.getLogger(PythonScriptRunner::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        try {
            runner.run(script.toString())
        } finally {
            logger.detachAppender(appender)
        }

        assertTrue(appender.list.any { "[python] live output" in it.formattedMessage })
    }

    @Test
    fun `preamble is evaluated before the main script`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("test.py")
        script.writeText("print(setup_value)")

        val output = runner.run(script.toString(), preamble = "setup_value = 'from preamble'")

        assertContains(output, "from preamble")
    }

    @Test
    fun `script can import ModuleUpdate from its own directory`(@TempDir tempDir: Path) {
        val archipelagoDir = tempDir.resolve("Archipelago")
        archipelagoDir.toFile().mkdirs()

        archipelagoDir.resolve("ModuleUpdate.py").writeText(
            """
            def check_pip():
                return True
            """.trimIndent(),
        )

        val script = archipelagoDir.resolve("Generate.py")
        script.writeText(
            """
            import ModuleUpdate
            ModuleUpdate.check_pip()
            print('ModuleUpdate.check_pip OK')
            """.trimIndent(),
        )

        val output = runner.run(script.toString())

        assertContains(output, "ModuleUpdate.check_pip OK")
    }
}
