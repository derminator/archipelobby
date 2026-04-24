package com.github.derminator.archipelobby.generator

/**
 * Holds the output of a successful Archipelago game generation.
 *
 * [archipelagoBytes] is the raw bytes of the .archipelago multidata file extracted from the
 * generator's output zip. [walkthroughBytes] is the spoiler log text; it is always non-null
 * for generated games (the generator is invoked with --spoiler 3) but may be null for games
 * that were uploaded without an accompanying spoiler file.
 */
data class GeneratedGame(val archipelagoBytes: ByteArray, val walkthroughBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GeneratedGame

        if (!archipelagoBytes.contentEquals(other.archipelagoBytes)) return false
        if (!walkthroughBytes.contentEquals(other.walkthroughBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = archipelagoBytes.contentHashCode()
        result = 31 * result + walkthroughBytes.contentHashCode()
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

    /**
     * Returns the number of locations in the player's game as determined by their YAML options,
     * or null if the count cannot be determined (unknown game, uninitialized submodule, etc.).
     *
     * @param yamlFilePath path to the player's saved YAML file
     * @param apWorldFilePaths paths to all APWorld files for the room (existing + newly uploaded)
     */
    suspend fun getLocationCount(yamlFilePath: String, apWorldFilePaths: List<String>): Int?
}
