package com.github.derminator.archipelobby.generator

/**
 * Holds the output of a successful Archipelago game generation.
 *
 * [archipelagoBytes] is the raw bytes of the .archipelago multidata file extracted from the
 * generator's output zip. [walkthroughBytes] is the spoiler log text; it is always non-null
 * for generated games (the generator is invoked with --spoiler 3) but may be null for games
 * that were uploaded without an accompanying spoiler file.
 */
data class GeneratedGame(val archipelagoBytes: ByteArray, val walkthroughBytes: ByteArray)

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
