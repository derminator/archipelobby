package com.github.derminator.archipelobby

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilsTest {

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zos ->
                for ((name, bytes) in files) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun `extracts multidata, spoiler, and every patch file`() {
        val zip = zipOf(
            "AP_1.archipelago" to "multidata".toByteArray(),
            "AP_1_Spoiler.txt" to "spoiler".toByteArray(),
            "AP_1_P1_Alice.apz3" to "alice-patch".toByteArray(),
            "AP_1_P2_Bob.apmanual" to "bob-patch".toByteArray(),
        )

        val result = extractFilesFromZip(zip)

        assert(result.archipelagoBytes != null)
        assert(result.archipelagoBytes!!.contentEquals("multidata".toByteArray()))
        assert(result.walkthroughBytes != null)
        assert(result.walkthroughBytes!!.contentEquals("spoiler".toByteArray()))
        assert(result.patchFiles.keys == setOf("AP_1_P1_Alice.apz3", "AP_1_P2_Bob.apmanual"))
        assert(result.patchFiles["AP_1_P1_Alice.apz3"]!!.contentEquals("alice-patch".toByteArray()))
        assert(result.patchFiles["AP_1_P2_Bob.apmanual"]!!.contentEquals("bob-patch".toByteArray()))
    }

    @Test
    fun `never drops an unfamiliar patch extension`() {
        val zip = zipOf(
            "AP_1.archipelago" to "multidata".toByteArray(),
            "AP_1_P1_Alice.apsomethingnew" to "weird-patch".toByteArray(),
        )

        val result = extractFilesFromZip(zip)

        assert(result.patchFiles.keys == setOf("AP_1_P1_Alice.apsomethingnew"))
    }

    @Test
    fun `keys patch files by base name, ignoring directories`() {
        val zip = zipOf(
            "output/AP_1.archipelago" to "multidata".toByteArray(),
            "output/AP_1_P1_Alice.apz3" to "alice-patch".toByteArray(),
        )

        val result = extractFilesFromZip(zip)

        assert(result.archipelagoBytes != null)
        assert(result.patchFiles.keys == setOf("AP_1_P1_Alice.apz3"))
    }

    @Test
    fun `zip with only an archipelago file yields no patches`() {
        val zip = zipOf("game.archipelago" to "multidata".toByteArray())

        val result = extractFilesFromZip(zip)

        assert(result.archipelagoBytes != null)
        assert(result.walkthroughBytes == null)
        assert(result.patchFiles.isEmpty())
    }
}
