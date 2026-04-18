package com.github.derminator.archipelobby.generator

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Isolated Python package scope for a single generation room.
 *
 * Packages listed in Archipelago world `requirements.txt` files (and any `requirements.txt`
 * carried inside `.apworld` zips) are installed via in-process `pip install --target` into a
 * dedicated `site-packages` directory under the room's work directory. The directory lives and
 * dies with the work directory, so rooms never share installed versions.
 *
 * Blacklisted distributions are dropped before pip is invoked. When pip fails on a package,
 * add its lowercase distribution name to `archipelobby.archipelago.package-blacklist`.
 */
class RoomPythonEnvironment(
    private val workDir: Path,
    private val blacklist: Set<String>,
    private val pythonScriptRunner: PythonScriptRunner,
) {
    private val logger = LoggerFactory.getLogger(RoomPythonEnvironment::class.java)

    val sitePackagesDir: Path = workDir.resolve("site-packages")

    fun prepare(archipelagoDir: Path, apWorldFiles: Map<String, ByteArray>) {
        Files.createDirectories(sitePackagesDir)
        val requirementSources = collectRequirements(archipelagoDir, apWorldFiles)
        if (requirementSources.isEmpty()) return

        val tempReqDir = Files.createDirectories(workDir.resolve("requirements"))
        for ((label, content) in requirementSources) {
            val filtered = filterBlacklisted(label, content)
            if (filtered.isBlank()) continue
            val tempReq = tempReqDir.resolve("$label.txt")
            tempReq.writeText(filtered)
            installRequirements(tempReq)
        }
    }

    private fun collectRequirements(
        archipelagoDir: Path,
        apWorldFiles: Map<String, ByteArray>,
    ): List<Pair<String, String>> {
        val sources = mutableListOf<Pair<String, String>>()
        val worldsDir = archipelagoDir.resolve("worlds")
        if (worldsDir.exists()) {
            for (worldDir in worldsDir.listDirectoryEntries()) {
                if (!Files.isDirectory(worldDir)) continue
                val reqFile = worldDir.resolve("requirements.txt")
                if (reqFile.exists()) {
                    sources.add("world-${worldDir.fileName}" to reqFile.readText())
                }
            }
        }
        for ((apName, bytes) in apWorldFiles) {
            extractRequirementsFromApWorld(apName, bytes)?.let { sources.add(it) }
        }
        return sources
    }

    private fun extractRequirementsFromApWorld(apName: String, bytes: ByteArray): Pair<String, String>? {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith("/requirements.txt")) {
                    val content = zis.readBytes().toString(Charsets.UTF_8)
                    val safeLabel = "apworld-" + apName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    return safeLabel to content
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    internal fun filterBlacklisted(label: String, content: String): String {
        val kept = mutableListOf<String>()
        val joined = joinContinuations(content.lines())
        for (line in joined) {
            val trimmed = line.substringBefore('#').trim()
            if (trimmed.isEmpty()) continue
            val distName = distributionName(trimmed)
            if (distName in blacklist) {
                logger.info("[{}] skipping blacklisted requirement '{}' (distribution '{}')", label, trimmed, distName)
            } else {
                kept.add(trimmed)
            }
        }
        return kept.joinToString("\n")
    }

    private fun joinContinuations(lines: List<String>): List<String> {
        val joined = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trimEnd()
            if (joined.isNotEmpty() && joined.last().endsWith("\\")) {
                joined[joined.lastIndex] = joined.last().dropLast(1).trimEnd() + " " + line.trim()
            } else {
                joined.add(line)
            }
        }
        return joined
    }

    private fun installRequirements(requirementsFile: Path) {
        val bootstrap = Files.createTempFile(workDir, "pip-install-", ".py")
        val requirementsPathLiteral = requirementsFile.toAbsolutePath().toString().replace("\\", "\\\\")
        val targetPathLiteral = sitePackagesDir.toAbsolutePath().toString().replace("\\", "\\\\")
        bootstrap.writeText(
            """
            import sys
            from pip._internal.cli.main import main as pip_main
            rc = pip_main([
                "install",
                "--no-input",
                "--disable-pip-version-check",
                "--target", "$targetPathLiteral",
                "--upgrade",
                "-r", "$requirementsPathLiteral",
            ])
            sys.exit(rc)
            """.trimIndent(),
        )
        try {
            pythonScriptRunner.run(bootstrap.toString())
        } finally {
            Files.deleteIfExists(bootstrap)
        }
    }

    companion object {
        internal fun distributionName(requirementLine: String): String =
            requirementLine
                .trim()
                .substringBefore("#egg=")
                .substringBefore("@")
                .substringBefore("[")
                .substringBefore(";")
                .split(Regex("[=<>!~\\s]"))[0]
                .trim()
                .lowercase()
    }
}
