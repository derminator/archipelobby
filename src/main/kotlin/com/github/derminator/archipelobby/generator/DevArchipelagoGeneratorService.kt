package com.github.derminator.archipelobby.generator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dev-mode stub that packages the submitted YAMLs into a ZIP to simulate generation,
 * without requiring Python or the Archipelago toolchain.
 */
@Service
@Profile("!prod")
class DevArchipelagoGeneratorService : ArchipelagoGeneratorService {

    override suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in yamlFiles) {
                zip.putNextEntry(ZipEntry("Players/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, bytes) in apWorldFiles) {
                zip.putNextEntry(ZipEntry("custom_worlds/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        out.toByteArray()
    }
}
