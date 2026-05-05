package com.github.derminator.archipelobby.generator

interface ArchipelagoGeneratorService {
    /**
     * Generates an Archipelago multiworld game from the provided player YAML files and APWorld files.
     *
     * @param yamlFiles map of filename → file content for each player's YAML
     * @param apWorldFiles map of filename → file content for each custom APWorld
     * @return the raw bytes of the generated .archipelago file
     */
    suspend fun generate(
        yamlFiles: Map<String, ByteArray>,
        apWorldFiles: Map<String, ByteArray>,
    ): ByteArray

    /**
     * Returns the number of locations in the player's game as determined by their YAML options,
     * or null if the count cannot be determined (unknown game, uninitialized submodule, etc.).
     *
     * @param yamlFilePath path to the player's saved YAML file
     * @param apWorldFilePaths paths to all APWorld files for the room (existing + newly uploaded)
     */
    suspend fun getLocationCount(yamlFilePath: String, apWorldFilePaths: List<String>): Int?
}
