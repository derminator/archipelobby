package com.github.derminator.archipelobby.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomPythonEnvironmentTest {

    @Test
    fun `distributionName strips version specifiers, extras, markers, and PEP 508 urls`() {
        assertEquals("colorama", RoomPythonEnvironment.distributionName("colorama==0.4.6"))
        assertEquals(
            "kivymd",
            RoomPythonEnvironment.distributionName("kivymd @ git+https://github.com/kivymd/KivyMD@5ff9d0d")
        )
        assertEquals(
            "requests",
            RoomPythonEnvironment.distributionName("requests[security] >= 2.0 ; python_version >= '3.8'")
        )
        assertEquals("typing-extensions", RoomPythonEnvironment.distributionName("typing-extensions>=4.7, <5"))
        assertEquals("numpy", RoomPythonEnvironment.distributionName("  NumPy ~= 1.26  "))
    }

    @Test
    fun `filterBlacklisted drops blacklisted distributions and preserves the rest`(@TempDir tempDir: Path) {
        val env = RoomPythonEnvironment(
            workDir = tempDir,
            blacklist = setOf("kivy", "kivymd"),
            pythonScriptRunner = PythonScriptRunner(),
        )

        val content = """
            # core
            colorama==0.4.6
            PyYAML==6.0.3
            kivy==2.3.1
            kivymd @ git+https://github.com/kivymd/KivyMD@5ff9d0d
            kivymd>=2.0.1.dev0

            typing_extensions==4.15.0
        """.trimIndent()

        val filtered = env.filterBlacklisted("core", content)

        val lines = filtered.lines().filter { it.isNotBlank() }
        assertTrue(lines.any { it.startsWith("colorama") })
        assertTrue(lines.any { it.startsWith("PyYAML") })
        assertTrue(lines.any { it.startsWith("typing_extensions") })
        assertFalse(lines.any { RoomPythonEnvironment.distributionName(it) == "kivy" })
        assertFalse(lines.any { RoomPythonEnvironment.distributionName(it) == "kivymd" })
    }

    @Test
    fun `filterBlacklisted joins backslash continuations before evaluating the distribution name`(@TempDir tempDir: Path) {
        val env = RoomPythonEnvironment(
            workDir = tempDir,
            blacklist = setOf("pyevermizer"),
            pythonScriptRunner = PythonScriptRunner(),
        )

        val content = """
            pyevermizer==0.50.1 \
              --hash=sha256:4d1f43d5f8016e7bfcb5cd80b447a4f278b60b1b250a6153e66150230bf280e8
            keep-me==1.0
        """.trimIndent()

        val filtered = env.filterBlacklisted("soe", content)

        val lines = filtered.lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("keep-me"))
    }

    @Test
    fun `prepare creates site-packages directory even when no requirements are discovered`(@TempDir tempDir: Path) {
        val env = RoomPythonEnvironment(
            workDir = tempDir,
            blacklist = emptySet(),
            pythonScriptRunner = PythonScriptRunner(),
        )
        val emptyArchipelagoDir = tempDir.resolve("Archipelago").also { Files.createDirectories(it) }

        env.prepare(emptyArchipelagoDir, emptyMap())

        assertTrue(Files.isDirectory(env.sitePackagesDir))
    }
}
