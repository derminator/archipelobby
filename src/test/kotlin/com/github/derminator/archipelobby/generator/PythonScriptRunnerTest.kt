package com.github.derminator.archipelobby.generator

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PythonScriptRunnerTest {

    private val runner = PythonScriptRunner(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
    )

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
    fun `Windows executable resolution is absolute`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))

        assertTrue(resolveWindowsExecutable("python").isAbsolute)
    }

    @Test
    fun `Windows child inherits only its standard stream handles`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val inheritedFile = tempDir.resolve("must-not-be-inherited.txt")
        inheritedFile.writeText("secret")
        val attributes = WinBase.SECURITY_ATTRIBUTES().also {
            it.dwLength = WinDef.DWORD(it.size().toLong())
            it.bInheritHandle = true
            it.write()
        }
        val handle = Kernel32.INSTANCE.CreateFile(
            inheritedFile.toString(),
            WinNT.GENERIC_READ,
            WinNT.FILE_SHARE_READ,
            attributes,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_ATTRIBUTE_NORMAL,
            null,
        )
        assertTrue(handle != WinBase.INVALID_HANDLE_VALUE)
        try {
            val rawHandle = Pointer.nativeValue(handle.pointer)
            val script = tempDir.resolve("handle_probe.py")
            script.writeText(
                """
                import msvcrt, os
                try:
                    fd = msvcrt.open_osfhandle($rawHandle, os.O_RDONLY)
                    os.read(fd, 1)
                    print("inherited")
                except OSError:
                    print("not inherited")
                """.trimIndent(),
            )

            val output = runner.run(script.toString())

            assertContains(output, "not inherited")
            assertFalse(output.lineSequence().any { it.trim() == "inherited" })
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    @Test
    fun `pip is importable from the subprocess Python environment`(@TempDir tempDir: Path) {
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
    fun `terminates scripts that exceed the configured timeout`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("slow.py")
        script.writeText("import time\ntime.sleep(10)")
        val shortTimeoutRunner = PythonScriptRunner(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
            timeoutSeconds = 1,
            maxOutputBytes = 1024,
        )

        val exception = assertThrows<ResponseStatusException> {
            shortTimeoutRunner.run(script.toString())
        }

        assertContains(exception.reason ?: "", "timed out")
    }

    @Test
    fun `timeout terminates child processes too`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("spawn_child.py")
        val pidFile = tempDir.resolve("child.pid")
        script.writeText(
            """
            import subprocess, sys, time
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
            with open(sys.argv[1], 'w') as handle:
                handle.write(str(child.pid))
                handle.flush()
            """.trimIndent(),
        )
        val shortTimeoutRunner = PythonScriptRunner(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
            timeoutSeconds = 1,
            maxOutputBytes = 1024,
        )

        assertThrows<ResponseStatusException> { shortTimeoutRunner.run(script.toString(), pidFile.toString()) }

        val childPid = pidFile.readText().trim().toLong()
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `rejects cumulative descendant churn beyond the configured limit`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("churn_children.py")
        script.writeText(
            """
            import subprocess, sys, time
            for _ in range(3):
                child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(0.08)'])
                child.wait()
                time.sleep(0.03)
            time.sleep(30)
            """.trimIndent(),
        )
        val boundedRunner = PythonScriptRunner(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
            timeoutSeconds = 2,
            maxOutputBytes = 1024,
            maxDescendants = 2,
        )

        val exception = assertThrows<ResponseStatusException> { boundedRunner.run(script.toString()) }

        assertContains(exception.reason ?: "", "descendant")
    }

    @Test
    fun `rejects scripts that exceed the descendant process limit`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("many_children.py")
        script.writeText(
            """
            import subprocess, sys, time
            children = [subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']) for _ in range(3)]
            time.sleep(30)
            """.trimIndent(),
        )
        val boundedRunner = PythonScriptRunner(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
            timeoutSeconds = 10,
            maxOutputBytes = 1024,
            maxDescendants = 2,
        )

        val exception = assertThrows<ResponseStatusException> { boundedRunner.run(script.toString()) }

        assertContains(exception.reason ?: "", "descendant")
    }

    @Test
    fun `terminates scripts whose output exceeds the configured limit`(@TempDir tempDir: Path) {
        val script = tempDir.resolve("noisy.py")
        script.writeText("print('x' * 10000)")
        val boundedRunner = PythonScriptRunner(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
            timeoutSeconds = 10,
            maxOutputBytes = 100,
        )

        val exception = assertThrows<ResponseStatusException> {
            boundedRunner.run(script.toString())
        }

        assertContains(exception.reason ?: "", "output limit")
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
