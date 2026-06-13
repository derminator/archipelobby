package com.github.derminator.archipelobby

import java.util.zip.ZipInputStream

/**
 * The contents of an Archipelago output zip that we care about.
 *
 * [archipelagoBytes] is the `.archipelago` multidata and [walkthroughBytes] is the spoiler
 * `.txt`. [patchFiles] holds every other (non-directory) file in the zip, keyed by base
 * filename — these are the per-slot patch files (e.g. `.apz3`, `.apsm`, `.apmanual`). It is a
 * deliberate denylist rather than an allowlist: anything that is not the multidata or the
 * spoiler is treated as a patch so that no patch is ever dropped, even for unfamiliar
 * extensions.
 */
data class ExtractedZipContents(
    val archipelagoBytes: ByteArray?,
    val walkthroughBytes: ByteArray?,
    val patchFiles: Map<String, ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExtractedZipContents

        if (!archipelagoBytes.contentEquals(other.archipelagoBytes)) return false
        if (!walkthroughBytes.contentEquals(other.walkthroughBytes)) return false
        if (patchFiles.keys != other.patchFiles.keys) return false
        return patchFiles.all { (name, bytes) -> bytes.contentEquals(other.patchFiles[name]) }
    }

    override fun hashCode(): Int {
        var result = archipelagoBytes.contentHashCode()
        result = 31 * result + walkthroughBytes.contentHashCode()
        result = 31 * result + patchFiles.keys.hashCode()
        return result
    }
}

fun extractFilesFromZip(zipBytes: ByteArray): ExtractedZipContents {
    var archipelagoBytes: ByteArray? = null
    var walkthroughBytes: ByteArray? = null
    val patchFiles = LinkedHashMap<String, ByteArray>()
    ZipInputStream(zipBytes.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val baseName = entry.name.substringAfterLast('/')
                when {
                    baseName.endsWith(".archipelago") -> archipelagoBytes = zis.readBytes()
                    baseName.endsWith(".txt") -> walkthroughBytes = zis.readBytes()
                    else -> patchFiles[baseName] = zis.readBytes()
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return ExtractedZipContents(archipelagoBytes, walkthroughBytes, patchFiles)
}
