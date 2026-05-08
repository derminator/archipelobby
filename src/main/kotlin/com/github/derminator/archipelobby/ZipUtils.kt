package com.github.derminator.archipelobby

import java.util.zip.ZipInputStream

fun extractFilesFromZip(zipBytes: ByteArray): Pair<ByteArray?, ByteArray?> {
    var archipelagoBytes: ByteArray? = null
    var walkthroughBytes: ByteArray? = null
    ZipInputStream(zipBytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                when {
                    entry.name.endsWith(".archipelago") -> archipelagoBytes = zis.readBytes()
                    entry.name.endsWith(".txt") -> walkthroughBytes = zis.readBytes()
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return Pair(archipelagoBytes, walkthroughBytes)
}
