package com.github.derminator.archipelobby

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Limits applied while inflating any uploaded or generated ZIP archive. */
data class ZipResourceLimits(
    val maxCompressedBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 256,
    val maxEntryNameLength: Int = 512,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    val maxCompressionRatio: Long = 100,
) {
    init {
        require(maxCompressedBytes > 0)
        require(maxEntries > 0)
        require(maxEntryNameLength > 0)
        require(maxEntryBytes > 0)
        require(maxTotalBytes > 0)
        require(maxCompressionRatio > 0)
    }
}

/**
 * The contents of an Archipelago output zip that we care about.
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

fun extractFilesFromZip(
    zipBytes: ByteArray,
    limits: ZipResourceLimits = ZipResourceLimits(),
): ExtractedZipContents {
    var archipelagoBytes: ByteArray? = null
    var walkthroughBytes: ByteArray? = null
    val patchFiles = LinkedHashMap<String, ByteArray>()
    val extractedNames = HashSet<String>()

    visitZipEntries(zipBytes, limits) { entry, bytes ->
        val baseName = safeDownloadFileName(entry.name)
        if (!extractedNames.add(baseName.lowercase(Locale.ROOT))) {
            rejectArchive("ZIP contains duplicate output filename: $baseName")
        }
        when {
            baseName.endsWith(".archipelago", ignoreCase = true) -> {
                if (archipelagoBytes != null) rejectArchive("ZIP contains multiple .archipelago files")
                archipelagoBytes = bytes
            }
            baseName.endsWith(".txt", ignoreCase = true) -> {
                if (walkthroughBytes != null) rejectArchive("ZIP contains multiple walkthrough files")
                walkthroughBytes = bytes
            }
            else -> patchFiles[baseName] = bytes
        }
        true
    }
    return ExtractedZipContents(archipelagoBytes, walkthroughBytes, patchFiles)
}

/** Returns the first matching entry while enforcing limits on every entry read. */
fun findZipEntryBytes(
    zipBytes: ByteArray,
    limits: ZipResourceLimits = ZipResourceLimits(),
    maxMatchingEntryBytes: Long = limits.maxEntryBytes,
    predicate: (ZipEntry) -> Boolean,
): ByteArray? {
    require(maxMatchingEntryBytes > 0)
    var result: ByteArray? = null
    visitZipEntries(
        zipBytes,
        limits,
        entryByteLimit = { entry ->
            if (predicate(entry)) minOf(limits.maxEntryBytes, maxMatchingEntryBytes) else limits.maxEntryBytes
        },
    ) { entry, bytes ->
        if (predicate(entry)) {
            if (result != null) rejectArchive("ZIP contains multiple matching entries")
            result = bytes
        }
        true
    }
    return result
}

private fun visitZipEntries(
    zipBytes: ByteArray,
    limits: ZipResourceLimits,
    entryByteLimit: (ZipEntry) -> Long = { limits.maxEntryBytes },
    visitor: (ZipEntry, ByteArray) -> Boolean,
) {
    if (zipBytes.size.toLong() > limits.maxCompressedBytes) {
        rejectArchive("ZIP compressed size exceeds ${limits.maxCompressedBytes} bytes")
    }
    validateCentralDirectoryStructure(zipBytes, limits)

    var entryCount = 0
    var totalBytes = 0L
    var totalCompressedBytes = 0L
    val entryNames = HashSet<String>()
    try {
        SeekableInMemoryByteChannel(zipBytes).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        rejectArchive("ZIP entry count exceeds ${limits.maxEntries}")
                    }
                    if (entry.name.length > limits.maxEntryNameLength) {
                        rejectArchive("ZIP entry name exceeds ${limits.maxEntryNameLength} characters")
                    }
                    validateLocalHeaderName(zipBytes, entry)
                    val normalizedName = validateArchiveEntryName(entry.name, entry.isDirectory)
                        .lowercase(Locale.ROOT)
                    if (!entryNames.add(normalizedName)) {
                        rejectArchive("Duplicate ZIP entry name: ${entry.name}")
                    }
                    val compressedSize = entry.compressedSize
                    if (compressedSize < 0 || compressedSize > limits.maxCompressedBytes - totalCompressedBytes) {
                        rejectArchive("ZIP compressed entry data exceeds ${limits.maxCompressedBytes} bytes")
                    }
                    totalCompressedBytes += compressedSize

                    if (!zip.canReadEntryData(entry)) rejectArchive("ZIP entry uses an unsupported encoding")
                    val bytes = zip.getInputStream(entry).use { input ->
                        readBoundedEntry(input, entry, limits, totalBytes, entryByteLimit(entry))
                    }
                    totalBytes += bytes.size
                    if (!entry.isDirectory && !visitor(entry, bytes)) return
                }
            }
        }
    } catch (error: ResponseStatusException) {
        throw error
    } catch (error: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ZIP archive", error)
    }
}

private fun validateCentralDirectoryStructure(zipBytes: ByteArray, limits: ZipResourceLimits) {
    if (zipBytes.size < 22) rejectArchive("ZIP archive is truncated")
    val minimumOffset = maxOf(0, zipBytes.size - 65_557)
    val eocd = (zipBytes.size - 22 downTo minimumOffset).firstOrNull { offset ->
        readLeInt(zipBytes, offset) == 0x06054b50L &&
            offset + 22 + readLeShort(zipBytes, offset + 20) == zipBytes.size
    } ?: rejectArchive("ZIP end-of-central-directory record is missing or malformed")

    if (readLeShort(zipBytes, eocd + 4) != 0 || readLeShort(zipBytes, eocd + 6) != 0) {
        rejectArchive("Multi-disk ZIP archives are not supported")
    }
    val entriesOnDisk = readLeShort(zipBytes, eocd + 8)
    val entryCount = readLeShort(zipBytes, eocd + 10)
    val centralSize = readLeInt(zipBytes, eocd + 12)
    val centralOffset = readLeInt(zipBytes, eocd + 16)
    if (entriesOnDisk == 0xffff || entryCount == 0xffff ||
        centralSize == 0xffff_ffffL || centralOffset == 0xffff_ffffL
    ) {
        rejectArchive("ZIP64 archives are not supported")
    }
    if (entriesOnDisk != entryCount || entryCount > limits.maxEntries) {
        rejectArchive("ZIP entry count exceeds ${limits.maxEntries}")
    }
    val centralEnd = centralOffset + centralSize
    if (centralOffset > Int.MAX_VALUE || centralEnd != eocd.toLong()) {
        rejectArchive("ZIP central directory has invalid bounds")
    }

    var cursor = centralOffset.toInt()
    repeat(entryCount) {
        if (cursor > eocd - 46 || readLeInt(zipBytes, cursor) != 0x02014b50L) {
            rejectArchive("ZIP central directory entry is malformed")
        }
        val nameLength = readLeShort(zipBytes, cursor + 28)
        val extraLength = readLeShort(zipBytes, cursor + 30)
        val commentLength = readLeShort(zipBytes, cursor + 32)
        if (nameLength.toLong() > limits.maxEntryNameLength.toLong() * 4) {
            rejectArchive("ZIP entry name exceeds ${limits.maxEntryNameLength} characters")
        }
        val next = cursor.toLong() + 46 + nameLength + extraLength + commentLength
        if (next > centralEnd || next > Int.MAX_VALUE) {
            rejectArchive("ZIP central directory entry is truncated")
        }
        cursor = next.toInt()
    }
    if (cursor.toLong() != centralEnd) {
        rejectArchive("ZIP central directory contains uncounted entries or trailing data")
    }
}

private fun validateLocalHeaderName(zipBytes: ByteArray, entry: ZipArchiveEntry) {
    val offset = entry.localHeaderOffset
    if (offset < 0 || offset > Int.MAX_VALUE - 30L) rejectArchive("Invalid ZIP local header offset")
    val start = offset.toInt()
    if (start + 30 > zipBytes.size || readLeInt(zipBytes, start) != 0x04034b50L) {
        rejectArchive("Invalid ZIP local header")
    }
    val nameLength = readLeShort(zipBytes, start + 26)
    val extraLength = readLeShort(zipBytes, start + 28)
    val nameStart = start + 30
    val dataStart = nameStart.toLong() + nameLength + extraLength
    if (dataStart > zipBytes.size || nameStart + nameLength > zipBytes.size) {
        rejectArchive("Truncated ZIP local header")
    }
    val localRawName = zipBytes.copyOfRange(nameStart, nameStart + nameLength)
    val centralRawName = entry.rawName
    if (centralRawName == null || !localRawName.contentEquals(centralRawName)) {
        rejectArchive("ZIP local and central entry names differ")
    }
}

private fun readLeShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readLeInt(bytes: ByteArray, offset: Int): Long =
    readLeShort(bytes, offset).toLong() or (readLeShort(bytes, offset + 2).toLong() shl 16)

private fun readBoundedEntry(
    input: InputStream,
    entry: ZipEntry,
    limits: ZipResourceLimits,
    totalBeforeEntry: Long,
    maxEntryBytes: Long,
): ByteArray {
    require(maxEntryBytes > 0)
    val initialCapacity = entry.size.takeIf { it in 1..minOf(64L * 1024, maxEntryBytes) }
        ?.toInt() ?: 8192
    val output = ByteArrayOutputStream(initialCapacity)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var entryBytes = 0L

    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        entryBytes += count
        if (entryBytes > maxEntryBytes) {
            rejectArchive("ZIP entry size exceeds $maxEntryBytes bytes")
        }
        if (entryBytes > limits.maxTotalBytes - totalBeforeEntry) {
            rejectArchive("ZIP decompressed size exceeds ${limits.maxTotalBytes} bytes")
        }
        output.write(buffer, 0, count)
    }

    val compressedBytes = entry.compressedSize
    val minimumCompressedBytes = entryBytes / limits.maxCompressionRatio +
        if (entryBytes % limits.maxCompressionRatio == 0L) 0 else 1
    if (compressedBytes > 0 && compressedBytes < minimumCompressedBytes) {
        rejectArchive("ZIP entry compression ratio exceeds ${limits.maxCompressionRatio}:1")
    }
    return output.toByteArray()
}

/** Converts an untrusted display/client name into a single, portable filename. */
fun safeDownloadFileName(untrustedName: String, requiredExtension: String = ""): String {
    val baseName = untrustedName.replace('\\', '/').substringAfterLast('/')
    var safe = baseName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').take(128)
    if (safe.isBlank()) safe = "download"

    val extension = requiredExtension.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith('.')) it else ".$it" }
        .orEmpty()
    require(extension.isEmpty() || extension.matches(Regex("\\.[A-Za-z0-9]{1,15}"))) {
        "Unsafe required extension"
    }
    if (extension.isNotEmpty() && !safe.endsWith(extension, ignoreCase = true)) {
        safe = safe.take(128 - extension.length).trimEnd('.') + extension
    }
    return safe
}

/** Builds a bounded ZIP response and rejects duplicate or unsafe entry paths. */
fun createDownloadZip(
    entries: List<Pair<String, ByteArray>>,
    maxUncompressedBytes: Long,
    maxArchiveBytes: Long,
    maxEntries: Int,
): ByteArray {
    val output = ByteArrayOutputStream()
    DownloadZipWriter(output, maxUncompressedBytes, maxArchiveBytes, maxEntries).use { writer ->
        entries.forEach { (name, bytes) -> writer.add(name, bytes) }
    }
    return output.toByteArray()
}

/** Writes ZIP entries incrementally without retaining the source files or archive in memory. */
class DownloadZipWriter(
    output: OutputStream,
    private val maxUncompressedBytes: Long,
    maxArchiveBytes: Long,
    private val maxEntries: Int,
) : AutoCloseable {
    private val zip = ZipOutputStream(BoundedOutputStream(output, maxArchiveBytes))
    private val names = HashSet<String>()
    private var totalBytes = 0L
    private var entryCount = 0

    init {
        require(maxUncompressedBytes > 0 && maxArchiveBytes > 0 && maxEntries > 0)
    }

    fun add(name: String, bytes: ByteArray) {
        entryCount++
        if (entryCount > maxEntries) rejectArchive("Download ZIP entry count exceeds $maxEntries")
        val normalized = validateArchiveEntryName(name, isDirectory = false)
        if (!names.add(normalized.lowercase(Locale.ROOT))) {
            rejectArchive("Duplicate download ZIP entry name: $normalized")
        }
        if (bytes.size.toLong() > maxUncompressedBytes - totalBytes) {
            rejectArchive("Download ZIP decompressed size exceeds $maxUncompressedBytes bytes")
        }
        totalBytes += bytes.size
        zip.putNextEntry(ZipEntry(normalized))
        zip.write(bytes)
        zip.closeEntry()
    }

    override fun close() = zip.close()
}

private val WINDOWS_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL")

private fun validateArchiveEntryName(name: String, isDirectory: Boolean): String {
    val normalized = name.replace('\\', '/')
    val segments = normalized.split('/').let { parts ->
        if (isDirectory && parts.lastOrNull().isNullOrEmpty()) parts.dropLast(1) else parts
    }
    if (
        normalized.isBlank() ||
        normalized.startsWith('/') ||
        normalized.matches(Regex("^[A-Za-z]:.*")) ||
        normalized.any { it.code < 32 || it.code == 127 } ||
        segments.isEmpty() ||
        segments.any {
            val portableBase = it.trimEnd(' ', '.').substringBefore('.').uppercase()
            it.isBlank() ||
                it == "." ||
                it == ".." ||
                ':' in it ||
                it.endsWith(' ') ||
                it.endsWith('.') ||
                portableBase in WINDOWS_RESERVED_NAMES ||
                portableBase.matches(Regex("^(COM|LPT)[1-9]$"))
        }
    ) {
        rejectArchive("Unsafe ZIP entry name: $name")
    }
    return normalized
}

private class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
) : OutputStream() {
    private var written = 0L

    init {
        require(maxBytes > 0)
    }

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        reserve(length)
        delegate.write(bytes, offset, length)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    private fun reserve(count: Int) {
        require(count >= 0)
        if (count.toLong() > maxBytes - written) {
            rejectArchive("Download archive size exceeds $maxBytes bytes")
        }
        written += count
    }
}

private fun rejectArchive(reason: String): Nothing =
    throw ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, reason)
