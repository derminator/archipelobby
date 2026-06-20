package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.Entry
import org.junit.jupiter.api.Test

class PatchFilesTest {

    private fun entry(id: Long, name: String) = Entry(id, 1L, 0L, name, "Game", "path/$id.yaml")

    private fun patches(vararg names: String): Map<String, ByteArray> =
        names.associateWith { it.toByteArray() }

    private fun assignedNames(result: Map<Long, List<NamedPatch>>): Map<Long, Set<String>> =
        result.mapValues { (_, list) -> list.map { it.fileName }.toSet() }

    @Test
    fun `matches patches to entries by file-safe slot name`() {
        val entries = listOf(entry(1, "Alice"), entry(2, "Bob"))
        val result = matchPatchesToEntries(
            entries,
            patches("AP_42_P1_Alice.apz3", "AP_42_P2_Bob.apmanual"),
        )

        assert(assignedNames(result) == mapOf(1L to setOf("AP_42_P1_Alice.apz3"), 2L to setOf("AP_42_P2_Bob.apmanual")))
    }

    @Test
    fun `keeps multiple patches for a single slot`() {
        val entries = listOf(entry(1, "Alice"))
        val result = matchPatchesToEntries(
            entries,
            patches("AP_42_P1_Alice.apz3", "AP_42_P1_Alice.aplttp"),
        )

        assert(assignedNames(result) == mapOf(1L to setOf("AP_42_P1_Alice.apz3", "AP_42_P1_Alice.aplttp")))
    }

    @Test
    fun `matches slot names containing dots`() {
        val entries = listOf(entry(1, "Cool.Player"))
        val result = matchPatchesToEntries(entries, patches("AP_42_P1_Cool.Player.apz3"))

        assert(assignedNames(result) == mapOf(1L to setOf("AP_42_P1_Cool.Player.apz3")))
    }

    @Test
    fun `templated name falls back to player number rather than being dropped`() {
        // Entry.name is the un-expanded template; the generated slot name is "Player1".
        val entries = listOf(entry(1, "Player{number}"), entry(2, "Other{number}"))
        val result = matchPatchesToEntries(entries, patches("AP_42_P1_Player1.apz3"))

        // P1 maps to the first entry by id. Nothing is discarded.
        assert(assignedNames(result) == mapOf(1L to setOf("AP_42_P1_Player1.apz3")))
    }

    @Test
    fun `ambiguous sanitized collision falls back rather than being dropped`() {
        // "A/B" and "A\B" both sanitize to "AB" -> ambiguous, so name matching is skipped.
        val entries = listOf(entry(1, "A/B"), entry(2, "A\\B"))
        val result = matchPatchesToEntries(entries, patches("AP_42_P2_AB.apz3"))

        // Falls back to player number 2 -> second entry by id; the patch is still kept.
        assert(assignedNames(result) == mapOf(2L to setOf("AP_42_P2_AB.apz3")))
    }

    @Test
    fun `patch with no matching entry is still assigned`() {
        val entries = listOf(entry(1, "Alice"))
        val result = matchPatchesToEntries(entries, patches("AP_42_P1_Nobody.apz3"))

        assert(assignedNames(result) == mapOf(1L to setOf("AP_42_P1_Nobody.apz3")))
    }

    @Test
    fun `every input patch is assigned exactly once`() {
        val entries = listOf(entry(1, "Alice"), entry(2, "Bob"))
        val input = patches(
            "AP_42_P1_Alice.apz3",
            "AP_42_P2_Bob.apmanual",
            "AP_42_P3_Ghost.apz3",
            "weird-file-no-pattern.bin",
        )
        val result = matchPatchesToEntries(entries, input)

        val allAssigned = result.values.flatten().map { it.fileName }
        assert(allAssigned.size == input.size)
        assert(allAssigned.toSet() == input.keys)
    }

    @Test
    fun `empty entries yields empty result`() {
        val result = matchPatchesToEntries(emptyList(), patches("AP_42_P1_Alice.apz3"))
        assert(result.isEmpty())
    }
}
