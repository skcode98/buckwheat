package com.danilkinkin.buckwheat.widget.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceWidgetConfigTest {

    @Test
    fun `effectiveVoiceWidgetDesign prefers the per-instance override`() {
        assertEquals("RING", effectiveVoiceWidgetDesign("RING", "PERCENT"))
        assertEquals("GRAPH_BG", effectiveVoiceWidgetDesign("GRAPH_BG", "AMOUNT"))
    }

    @Test
    fun `effectiveVoiceWidgetDesign falls back to the global design when override is missing`() {
        assertEquals("PERCENT", effectiveVoiceWidgetDesign(null, "PERCENT"))
        assertEquals("AMOUNT", effectiveVoiceWidgetDesign(null, "AMOUNT"))
    }

    @Test
    fun `effectiveVoiceWidgetDesign falls back to the global design when override is blank`() {
        assertEquals("PERCENT", effectiveVoiceWidgetDesign("", "PERCENT"))
        assertEquals("PERCENT", effectiveVoiceWidgetDesign("   ", "PERCENT"))
    }

    @Test
    fun `effectiveVoiceWidgetDesign returns a valid design name for every override and global`() {
        VoiceWidgetDesign.entries.forEach { global ->
            listOf(null, "", VoiceWidgetDesign.PERCENT.name, VoiceWidgetDesign.GRAPH_BG.name)
                .forEach { override ->
                    val result = effectiveVoiceWidgetDesign(override, global.name)
                    assertEquals(
                        "override=$override global=${global.name}",
                        true,
                        VoiceWidgetDesign.entries.any { it.name == result },
                    )
                }
        }
    }
}
