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
}
