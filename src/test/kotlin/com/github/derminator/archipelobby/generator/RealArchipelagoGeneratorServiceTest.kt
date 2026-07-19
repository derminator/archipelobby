package com.github.derminator.archipelobby.generator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealArchipelagoGeneratorServiceTest {

    @Test
    fun `parses location count after world generation warning`() {
        val output = """
            WARNING:root:Manual_ResidentEvil4_VincentsSin has more items than locations. 400 non-progression items will be removed at random.
            760
        """.trimIndent()

        assertEquals(760, parseLocationCount(output))
    }

    @Test
    fun `rejects output whose final line is not a location count`() {
        assertNull(parseLocationCount("760\nunexpected output"))
    }
}
