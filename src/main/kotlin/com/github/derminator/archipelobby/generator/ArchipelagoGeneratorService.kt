package com.github.derminator.archipelobby.generator

/**
 * Holds the output of a successful Archipelago game generation.
 *
 * [archipelagoBytes] is the raw bytes of the .archipelago multidata file extracted from the
 * generator's output zip. [walkthroughBytes] is the spoiler log text; it is always non-null
 * for generated games (the generator is invoked with --spoiler 3) but may be null for games
 * that were uploaded without an accompanying spoiler file. [patchFiles] holds the per-slot
 * patch files (e.g. .apz3, .apsm, .apmanual) keyed by their filename, one or more per player
 * slot that produces a patch.
 */
data class GeneratedGame(
    val archipelagoBytes: ByteArray,
    val walkthroughBytes: ByteArray,
    val patchFiles: Map<String, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GeneratedGame

        if (!archipelagoBytes.contentEquals(other.archipelagoBytes)) return false
        if (!walkthroughBytes.contentEquals(other.walkthroughBytes)) return false
        if (patchFiles.keys != other.patchFiles.keys) return false
        if (!patchFiles.all { (name, bytes) -> bytes.contentEquals(other.patchFiles[name]) }) return false

        return true
    }

    override fun hashCode(): Int {
        var result = archipelagoBytes.contentHashCode()
        result = 31 * result + walkthroughBytes.contentHashCode()
        result = 31 * result + patchFiles.keys.hashCode()
        return result
    }
}

interface ArchipelagoGeneratorService {
    /**
     * Generates an Archipelago multiworld game from the provided player YAML files and APWorld files.
     *
     * @param yamlFiles map of filename → file content for each player's YAML
     * @param apWorldFiles map of filename → file content for each custom APWorld
     * @return a [GeneratedGame] containing the .archipelago multidata and the spoiler log
     */
    suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): GeneratedGame
}
