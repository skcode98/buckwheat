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
    fun `parseVoiceInputs splits comma separated records`() {
        val results = parseVoiceInputs("tea 20, lunch 150, dinner 300")

        assertEquals(3, results.size)
        assertEquals("20", results[0].amount)
        assertEquals("tea", results[0].comment)
        assertEquals("150", results[1].amount)
        assertEquals("lunch", results[1].comment)
        assertEquals("300", results[2].amount)
        assertEquals("dinner", results[2].comment)
    }

    @Test
    fun `parseVoiceInputs splits on and between numbered chunks`() {
        val results = parseVoiceInputs("coffee 5 dollars and a sandwich 3")

        assertEquals(2, results.size)
        assertEquals("5", results[0].amount)
        assertEquals("coffee", results[0].comment)
        assertEquals("3", results[1].amount)
        assertEquals("a sandwich", results[1].comment)
    }

    @Test
    fun `parseVoiceInputs keeps comment with and when one side has no number`() {
        val results = parseVoiceInputs("bread and butter 50")

        assertEquals(1, results.size)
        assertEquals("50", results[0].amount)
        assertEquals("bread and butter", results[0].comment)
    }

    @Test
    fun `parseVoiceInputs keeps decimal comma as one record`() {
        val results = parseVoiceInputs("12,50")

        assertEquals(1, results.size)
        assertEquals("12,50", results[0].amount)
    }

    @Test
    fun `parseVoiceInputs keeps thousands separator as one record`() {
        val results = parseVoiceInputs("1,234 rupees and 5 tea")

        assertEquals(2, results.size)
        assertEquals("1,234", results[0].amount)
        assertEquals("5", results[1].amount)
    }

    @Test
    fun `parseVoiceInputs empty or numberless returns empty`() {
        assertEquals(0, parseVoiceInputs("").size)
        assertEquals(0, parseVoiceInputs("   ").size)
        assertEquals(0, parseVoiceInputs("nothing here").size)
    }

    @Test
    fun `parseVoiceAiContents parses array of records`() {
        val results = parseVoiceAiContents(
            """[{"amount":"150","comment":"tea","date":"today"},""" +
                """{"amount":"45","comment":"bus","date":"yesterday"}]"""
        )

        assertEquals(2, results.size)
        assertEquals("150", results[0].amount)
        assertEquals("tea", results[0].comment)
        assertEquals("45", results[1].amount)
        assertEquals("bus", results[1].comment)

        val expectedYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        assertEquals(
            expectedYesterday.get(Calendar.DAY_OF_MONTH),
            results[1].date.toCalendar().get(Calendar.DAY_OF_MONTH),
        )
    }

    @Test
    fun `parseVoiceAiContents parses array wrapped in markdown fences`() {
        val raw = "```json\n[{\"amount\":\"150\",\"comment\":\"tea\",\"date\":null}]\n```"
        val results = parseVoiceAiContents(raw)

        assertEquals(1, results.size)
        assertEquals("150", results[0].amount)
        assertEquals("tea", results[0].comment)
    }

    @Test
    fun `parseVoiceAiContents falls back to offline splitting on prose`() {
        val results = parseVoiceAiContents("tea 20 and lunch 150")

        assertEquals(2, results.size)
        assertEquals("20", results[0].amount)
        assertEquals("150", results[1].amount)
    }

    @Test
    fun `parseVoiceAiContents returns empty for empty or numberless reply`() {
        assertEquals(0, parseVoiceAiContents("").size)
        assertEquals(0, parseVoiceAiContents("no numbers here").size)
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

    @Test
    fun `extractModelContent unwraps chat completions envelope`() {
        val envelope = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"amount\":\"150\",\"comment\":\"tea\",\"date\":\"today\"}"
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            "{\"amount\":\"150\",\"comment\":\"tea\",\"date\":\"today\"}",
            extractModelContent(envelope),
        )
    }

    @Test
    fun `extractModelContent returns raw text when not an envelope`() {
        assertEquals("150 tea", extractModelContent("150 tea"))
    }

    @Test
    fun `extractModelContent handles missing content gracefully`() {
        val envelope = """{"choices":[{"message":{}}]}"""
        assertEquals(envelope, extractModelContent(envelope))
    }

    @Test
    fun `parseVoiceAiContent parses amount from envelope content json`() {
        val result = parseVoiceAiContent("""{"amount":"150","comment":"tea","date":"today"}""")

        assertEquals("150", result?.amount)
        assertEquals("tea", result?.comment)
    }

    @Test
    fun `parseVoiceAiContent reads amount with different casing`() {
        val result = parseVoiceAiContent("""{"Amount":"150","Comment":"lunch","Date":"today"}""")

        assertEquals("150", result?.amount)
        assertEquals("lunch", result?.comment)
    }

    @Test
    fun `parseVoiceAiContent falls back to prose when amount missing`() {
        val result = parseVoiceAiContent("user spent 150 rupees on tea")

        assertEquals("150", result?.amount)
        assertEquals("user spent on tea", result?.comment)
    }

    @Test
    fun `parseVoiceAiContent returns null for empty or numberless reply`() {
        assertNull(parseVoiceAiContent(""))
        assertNull(parseVoiceAiContent("no numbers here"))
        assertNull(parseVoiceAiContent("""{"comment":"tea","date":"today"}"""))
    }
}

private fun Date.toCalendar(): Calendar =
    Calendar.getInstance().apply { time = this@toCalendar }
