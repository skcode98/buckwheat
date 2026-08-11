package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class InterleavedScheduleCodecTest {

    private fun schedule(
        name: String = "FOOD",
        frequency: CategoryFrequency = CategoryFrequency.MONTHLY,
        anchorEpochDay: Long = 0,
    ) = InterleavedCategory(name, BigDecimal.ZERO, frequency, anchorEpochDay)

    @Test
    fun `schedules codec round-trips`() {
        val schedules = mapOf(
            "FOOD" to schedule("FOOD", CategoryFrequency.MONTHLY, 100),
            "HEALTH" to schedule("HEALTH", CategoryFrequency.QUARTERLY, 200),
            "rent" to schedule("rent", CategoryFrequency.ANNUAL, 300),
        )
        assertEquals(schedules, parseCategorySchedules(serializeCategorySchedules(schedules)))
    }

    @Test
    fun `schedules codec handles empty and null`() {
        assertEquals(emptyMap<String, InterleavedCategory>(), parseCategorySchedules(null))
        assertEquals(emptyMap<String, InterleavedCategory>(), parseCategorySchedules(""))
        assertEquals(
            emptyMap<String, InterleavedCategory>(),
            parseCategorySchedules(serializeCategorySchedules(emptyMap())),
        )
    }

    @Test
    fun `schedules codec skips malformed entries`() {
        val parsed = parseCategorySchedules(
            "FOOD:MONTHLY:100;garbage;HEALTH:BOGUS:200;:MONTHLY:100;TRAVEL:MONTHLY:notanumber;"
        )
        assertEquals(setOf("FOOD"), parsed.keys)
        assertEquals(CategoryFrequency.MONTHLY, parsed["FOOD"]?.frequency)
        assertEquals(100L, parsed["FOOD"]?.anchorEpochDay)
    }

    @Test
    fun `legacy notified entries default to the sentinel window`() {
        val parsed = parseCategoryCapNotifiedWithWindow("FOOD:1;SHOPPING:2")
        assertEquals(1 to Long.MIN_VALUE, parsed["FOOD"])
        assertEquals(2 to Long.MIN_VALUE, parsed["SHOPPING"])
    }

    @Test
    fun `windowed notified entries keep their window start`() {
        val parsed = parseCategoryCapNotifiedWithWindow("FOOD:2@17890")
        assertEquals(2 to 17890L, parsed["FOOD"])
    }

    @Test
    fun `windowed notified serializer keeps plain format for the sentinel`() {
        assertEquals(
            "FOOD:1;SHOPPING:2",
            serializeCategoryCapNotifiedWithWindow(
                mapOf(
                    "FOOD" to (1 to Long.MIN_VALUE),
                    "SHOPPING" to (2 to Long.MIN_VALUE),
                )
            )
        )
    }

    @Test
    fun `windowed notified serializer emits the window suffix`() {
        assertEquals(
            "FOOD:2@17890",
            serializeCategoryCapNotifiedWithWindow(mapOf("FOOD" to (2 to 17890L))),
        )
    }

    @Test
    fun `windowed notified serializer drops zero buckets`() {
        assertEquals(
            "",
            serializeCategoryCapNotifiedWithWindow(mapOf("FOOD" to (0 to 17890L))),
        )
    }

    @Test
    fun `plain parser works on windowed entries`() {
        assertEquals(mapOf("FOOD" to 2), parseCategoryCapNotified("FOOD:2@17890"))
        assertEquals(mapOf("FOOD" to 1), parseCategoryCapNotified("FOOD:1"))
    }

    @Test
    fun `windowed parser skips malformed entries`() {
        val parsed = parseCategoryCapNotifiedWithWindow("FOOD:2@17890;BAD;EMPTY:")
        assertEquals(setOf("FOOD"), parsed.keys)
        assertEquals(2 to 17890L, parsed["FOOD"])
    }
}
