package com.danilkinkin.buckwheat.keyboard

import java.util.Calendar
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceInputParserTest {

    @Test
    fun `simple amount and comment`() {
        val result = parseVoiceInput("tea 20 now")

        assertEquals("20", result?.amount)
        assertEquals("tea", result?.comment)
    }

    @Test
    fun `amount after a quantity picks the last number`() {
        val result = parseVoiceInput("2 coffees 150")

        assertEquals("150", result?.amount)
        assertEquals("2 coffees", result?.comment)
    }

    @Test
    fun `amount with trailing currency word`() {
        val result = parseVoiceInput("lunch 150 rupees")

        assertEquals("150", result?.amount)
        assertEquals("lunch", result?.comment)
    }

    @Test
    fun `currency-anchored amount wins over a later bare number`() {
        val result = parseVoiceInput("coffee 5 dollars and a sandwich 3")

        assertEquals("5", result?.amount)
    }

    @Test
    fun `decimal point amount`() {
        val result = parseVoiceInput("coffee 2.5")

        assertEquals("2.5", result?.amount)
    }

    @Test
    fun `thousands separator is preserved`() {
        val result = parseVoiceInput("1,234 rupees")

        assertEquals("1,234", result?.amount)
    }

    @Test
    fun `decimal comma is preserved`() {
        val result = parseVoiceInput("12,50")

        assertEquals("12,50", result?.amount)
    }

    @Test
    fun `single number with comment before it`() {
        val result = parseVoiceInput("150 lunch")

        assertEquals("150", result?.amount)
        assertEquals("lunch", result?.comment)
    }

    @Test
    fun `time of day is not picked as the amount`() {
        val result = parseVoiceInput("5pm coffee 5")

        assertEquals("5", result?.amount)
        assertEquals("coffee", result?.comment)
    }

    @Test
    fun `time with at prefix and am`() {
        val result = parseVoiceInput("dinner 300 at 7 am")

        assertEquals("300", result?.amount)
        assertEquals("dinner", result?.comment)
        assertEquals(7, result?.date?.toCalendar()?.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result?.date?.toCalendar()?.get(Calendar.MINUTE))
    }

    @Test
    fun `time with pm suffix and quantity comment`() {
        val result = parseVoiceInput("2 coffees 150 at 5pm")

        assertEquals("150", result?.amount)
        assertEquals("2 coffees", result?.comment)
        assertEquals(17, result?.date?.toCalendar()?.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result?.date?.toCalendar()?.get(Calendar.MINUTE))
    }

    @Test
    fun `bare quantity number is not a time`() {
        val now = Calendar.getInstance()
        val result = parseVoiceInput("2 coffees 150")

        assertEquals(
            now.get(Calendar.HOUR_OF_DAY),
            result?.date?.toCalendar()?.get(Calendar.HOUR_OF_DAY),
        )
    }

    @Test
    fun `yesterday shifts the date back one day`() {
        val expected = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val result = parseVoiceInput("lunch 150 yesterday")

        assertEquals("150", result?.amount)
        assertEquals(expected.get(Calendar.YEAR), result?.date?.toCalendar()?.get(Calendar.YEAR))
        assertEquals(expected.get(Calendar.MONTH), result?.date?.toCalendar()?.get(Calendar.MONTH))
        assertEquals(expected.get(Calendar.DAY_OF_MONTH), result?.date?.toCalendar()?.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `tomorrow shifts the date forward one day`() {
        val expected = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val result = parseVoiceInput("lunch 150 tomorrow")

        assertEquals("150", result?.amount)
        assertEquals(expected.get(Calendar.YEAR), result?.date?.toCalendar()?.get(Calendar.YEAR))
        assertEquals(expected.get(Calendar.MONTH), result?.date?.toCalendar()?.get(Calendar.MONTH))
        assertEquals(expected.get(Calendar.DAY_OF_MONTH), result?.date?.toCalendar()?.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `yesterday with time keeps the shifted day`() {
        val expected = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val result = parseVoiceInput("lunch 150 yesterday at 2pm")

        assertEquals("150", result?.amount)
        assertEquals("lunch", result?.comment)
        assertEquals(14, result?.date?.toCalendar()?.get(Calendar.HOUR_OF_DAY))
        assertEquals(expected.get(Calendar.YEAR), result?.date?.toCalendar()?.get(Calendar.YEAR))
        assertEquals(expected.get(Calendar.MONTH), result?.date?.toCalendar()?.get(Calendar.MONTH))
        assertEquals(expected.get(Calendar.DAY_OF_MONTH), result?.date?.toCalendar()?.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parseVoiceInput(""))
        assertNull(parseVoiceInput("   "))
    }

    @Test
    fun `input without a number returns null`() {
        assertNull(parseVoiceInput("nothing here"))
    }

    @Test
    fun `ai date parses iso offset date time`() {
        val now = Calendar.getInstance().time
        val parsed = parseVoiceAiDate("2026-08-05T10:30:00Z", now)

        assertEquals(java.time.Instant.parse("2026-08-05T10:30:00Z"), parsed.toInstant())
    }

    @Test
    fun `ai date parses iso local date time without offset`() {
        val now = Calendar.getInstance().time
        val parsed = parseVoiceAiDate("2026-08-05T10:30:00", now)
        val calendar = Calendar.getInstance().apply { time = parsed }

        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(5, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `ai date parses space separated date time with seconds`() {
        val now = Calendar.getInstance().time
        val parsed = parseVoiceAiDate("2026-08-05 10:30:00", now)
        val calendar = Calendar.getInstance().apply { time = parsed }

        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(5, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `ai date parses plain date as start of day`() {
        val now = Calendar.getInstance().time
        val parsed = parseVoiceAiDate("2026-08-05", now)
        val calendar = Calendar.getInstance().apply { time = parsed }

        assertEquals(2026, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, calendar.get(Calendar.MONTH))
        assertEquals(5, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun `ai date relative words use the now anchor`() {
        val anchor = Calendar.getInstance()
        anchor.set(2026, Calendar.MARCH, 10, 12, 0, 0)
        anchor.set(Calendar.MILLISECOND, 0)
        val now = anchor.time

        val yesterday = anchor.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val tomorrow = anchor.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)

        assertEquals(yesterday.time, parseVoiceAiDate("yesterday", now))
        assertEquals(tomorrow.time, parseVoiceAiDate("tomorrow", now))
        assertEquals(now, parseVoiceAiDate("today", now))
    }

    @Test
    fun `ai date falls back to now on blank or garbage`() {
        val now = Calendar.getInstance().time

        assertEquals(now, parseVoiceAiDate("", now))
        assertEquals(now, parseVoiceAiDate("   ", now))
        assertEquals(now, parseVoiceAiDate("not a date", now))
    }
}

private fun Date.toCalendar(): Calendar =
    Calendar.getInstance().apply { time = this@toCalendar }
