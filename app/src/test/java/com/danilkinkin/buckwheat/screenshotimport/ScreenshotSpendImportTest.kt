package com.danilkinkin.buckwheat.screenshotimport

import com.danilkinkin.buckwheat.keyboard.VoiceInputResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Calendar

class ScreenshotSpendImportTest {

    @Test
    fun `normalizeOcrText trims collapses blank lines and normalizes newlines`() {
        val raw = "  Coffee  20  \r\n\r\n  \nLunch 150\rTaxi\t300  "
        val normalized = normalizeOcrText(raw)
        assertEquals("Coffee  20\nLunch 150\nTaxi\t300", normalized)
    }

    @Test
    fun `normalizeOcrText empty returns empty`() {
        assertEquals("", normalizeOcrText(" \n  \r\n "))
    }

    @Test
    fun `draftsFromVoiceResults parses valid positive amounts and trims comment`() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 5, 10, 30, 0); set(Calendar.MILLISECOND, 0) }
        val results = listOf(
            VoiceInputResult(amount = "20", comment = " tea ", date = cal.time),
            VoiceInputResult(amount = "150.50", comment = "lunch", date = cal.time),
            VoiceInputResult(amount = "1,234", comment = "shopping", date = cal.time),
            VoiceInputResult(amount = "12,50", comment = "snack", date = cal.time),
        )
        val drafts = draftsFromVoiceResults(results)
        assertEquals(4, drafts.size)
        assertEquals(0, BigDecimal("20").compareTo(drafts[0].amount))
        assertEquals("tea", drafts[0].comment)
        assertEquals(0, BigDecimal("150.50").compareTo(drafts[1].amount))
        assertEquals(0, BigDecimal("1234").compareTo(drafts[2].amount))
        assertEquals(0, BigDecimal("12.50").compareTo(drafts[3].amount))
    }

    @Test
    fun `draftsFromVoiceResults drops invalid zero and non-numeric amounts`() {
        val now = Calendar.getInstance().time
        val results = listOf(
            VoiceInputResult(amount = "0", comment = "zero", date = now),
            VoiceInputResult(amount = "0.00", comment = "zero-decimal", date = now),
            VoiceInputResult(amount = "abc", comment = "bad", date = now),
            VoiceInputResult(amount = "", comment = "empty", date = now),
            VoiceInputResult(amount = "42", comment = "ok", date = now),
        )
        val drafts = draftsFromVoiceResults(results)
        assertEquals(1, drafts.size)
        assertEquals(0, BigDecimal("42").compareTo(drafts[0].amount))
        assertEquals("ok", drafts[0].comment)
    }

    @Test
    fun `buildScreenshotSystemPrompt requests JSON array spend extraction`() {
        val prompt = buildScreenshotSystemPrompt()
        assertTrue(prompt.contains("JSON array"))
        assertTrue(prompt.contains("amount"))
        assertTrue(prompt.contains("comment"))
        assertTrue(prompt.contains("date"))
        assertTrue(prompt.contains("receipt") || prompt.contains("screenshot"))
        assertTrue(prompt.contains("Ignore totals"))
    }

    @Test
    fun `buildScreenshotUserPrompt includes ocr text`() {
        val prompt = buildScreenshotUserPrompt("  Coffee 20\nLunch 150  ")
        assertTrue(prompt.startsWith("OCR text:\n"))
        assertTrue(prompt.contains("Coffee 20\nLunch 150"))
    }
}
