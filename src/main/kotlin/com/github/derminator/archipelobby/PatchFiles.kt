package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.Entry
import org.slf4j.LoggerFactory

/**
 * A single patch file extracted from an Archipelago output zip: the original filename (which
 * carries the game-specific extension, e.g. `AP_12345_P1_Alice.apz3`) and its bytes.
 */
data class NamedPatch(val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NamedPatch
        return fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

private val log = LoggerFactory.getLogger("com.github.derminator.archipelobby.PatchFiles")

// Characters Archipelago's Utils.get_file_safe_name strips when building output filenames.
private val FILE_UNSAFE_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

// Archipelago names per-slot output files `AP_<seed>_P<player>_<file-safe slot name>.<ext>`.
private val PATCH_SLOT_NAME_REGEX = Regex("""^AP_.+_P\d+_(.+)\.[^.]+$""")
private val PATCH_PLAYER_NUMBER_REGEX = Regex("""^AP_.+_P(\d+)_""")

/** Mirrors Archipelago's `Utils.get_file_safe_name` (best-effort; the submodule isn't vendored). */
fun fileSafeName(name: String): String = name.filterNot { it in FILE_UNSAFE_CHARS }

/** Extracts the file-safe slot name from a patch filename, or null if it doesn't match. */
fun slotNameFromPatchFilename(fileName: String): String? =
    PATCH_SLOT_NAME_REGEX.matchEntire(fileName)?.groupValues?.get(1)

/** Extracts the 1-based player number from a patch filename, or null if it doesn't match. */
fun playerNumberFromPatchFilename(fileName: String): Int? =
    PATCH_PLAYER_NUMBER_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Assigns every patch file to a slot ([Entry]). The guiding principle is that no patch is ever
 * discarded: a confident match is made by the file-safe slot name, and anything that can't be
 * matched confidently (templated `{number}`/`{player}` names, sanitization collisions, or no
 * matching entry) falls back to the player number embedded in the filename — the best guess
 * is logged so a possible mismatch is visible.
 *
 * @return a map from entry id to the patch files assigned to it. Every input patch appears
 *   exactly once across the returned lists. Returns an empty map when [entries] is empty.
 */
fun matchPatchesToEntries(
    entries: List<Entry>,
    patchFiles: Map<String, ByteArray>,
): Map<Long, List<NamedPatch>> {
    if (entries.isEmpty() || patchFiles.isEmpty()) return emptyMap()

    val sortedEntryIds = entries.mapNotNull { it.id }.sorted()
    if (sortedEntryIds.isEmpty()) return emptyMap()

    val entriesBySafeName: Map<String, List<Entry>> = entries
        .filter { it.id != null }
        .groupBy { fileSafeName(it.name) }

    val result = LinkedHashMap<Long, MutableList<NamedPatch>>()
    for ((fileName, bytes) in patchFiles) {
        val patch = NamedPatch(fileName, bytes)
        val slotName = slotNameFromPatchFilename(fileName)
        val matches = slotName?.let { entriesBySafeName[it] }

        val entryId = if (matches != null && matches.size == 1) {
            matches.single().id ?: error("Entry is not saved")
        } else {
            // Never drop: fall back to the player number, else the first slot.
            val playerNumber = playerNumberFromPatchFilename(fileName)
            val fallbackId = playerNumber
                ?.takeIf { it in 1..sortedEntryIds.size }
                ?.let { sortedEntryIds[it - 1] }
                ?: sortedEntryIds.first()
            log.warn(
                "Could not confidently match patch '{}' (slot '{}') to a single entry; " +
                    "falling back to entry id {}.",
                fileName, slotName, fallbackId,
            )
            fallbackId
        }
        result.getOrPut(entryId) { mutableListOf() }.add(patch)
    }
    return result
}
