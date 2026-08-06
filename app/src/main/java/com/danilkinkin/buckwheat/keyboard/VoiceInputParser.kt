package com.danilkinkin.buckwheat.keyboard

import java.util.Calendar
import java.util.Date
import java.util.Locale

data class VoiceInputResult(
    val amount: String,
    val comment: String,
    val date: Date,
)

private val AMOUNT_REGEX = Regex(
    // Optional leading currency symbol, the number, then an optional trailing currency
    // word/symbol. Group 2 always holds the raw digits (including any thousands/decimal
    // separator) so the downstream parseAmountToBigDecimal can distinguish "1,234"
    // (thousands) from "12,50" (decimal comma) instead of blindly swapping "," for ".".
    """([₹$€£¥])?\s*(\d+(?:[.,]\d+)?)\s*(rs|rupees?|rupay|bucks?|dollars?|[₹$€£¥])?""",
    RegexOption.IGNORE_CASE,
)

private val TIME_REGEX = Regex(
    """(?:(at)\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""",
    RegexOption.IGNORE_CASE,
)

private val FILLER_WORDS_REGEX = Regex(
    """\b(now|today|yesterday|kal|tomorrow|rs|rupees?|rupay|bucks?|dollars?|at|am|pm|a\.m\.|p\.m\.)\b""",
    RegexOption.IGNORE_CASE,
)

// Strong record separators: commas/semicolons must touch whitespace on at least one side so a
// thousands separator ("1,234") or decimal comma ("12,50") is never mistaken for a boundary.
private val STRONG_SEPARATOR_REGEX = Regex("""\s+[,;]\s*|[,;]\s+|\n+""")

private val WORD_SEPARATOR_REGEX = Regex("""\s+(?:and|then)\s+""", RegexOption.IGNORE_CASE)

private fun MatchResult.hasCurrencyMarker(): Boolean =
    groupValues[1].isNotEmpty() || groupValues[3].isNotEmpty()

fun parseVoiceInput(input: String): VoiceInputResult? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val now = Calendar.getInstance()
    val targetDate = Calendar.getInstance()

    val lower = trimmed.lowercase(Locale.getDefault())

    val isYesterday = lower.contains("yesterday") || lower.contains("kal")
    val isTomorrow = lower.contains("tomorrow")
    if (isYesterday) {
        targetDate.add(Calendar.DAY_OF_YEAR, -1)
    }
    if (isTomorrow) {
        targetDate.add(Calendar.DAY_OF_YEAR, 1)
    }

    // Only treat a number as a time when there is a strong signal: an "at" prefix,
    // a colon-separated minute, or an explicit am/pm suffix. A bare number in the
    // comment (e.g. "2 coffees") must not be interpreted as a time of day. The time
    // is stripped before the amount is chosen so "5pm" is never picked as the amount.
    val timeMatch = TIME_REGEX.findAll(trimmed).firstOrNull { match ->
        match.groupValues[1].isNotEmpty() ||
                match.groupValues[3].isNotEmpty() ||
                match.groupValues[4].isNotEmpty()
    }

    var working = trimmed
    if (timeMatch != null) {
        working = working.removeRange(timeMatch.range).trim()
        var hour = timeMatch.groupValues[2].toInt()
        val minute = timeMatch.groupValues[3].let { if (it.isEmpty()) 0 else it.toInt() }
        val ampm = timeMatch.groupValues[4].lowercase()

        when {
            ampm.startsWith("pm") && hour < 12 -> hour += 12
            ampm.startsWith("am") && hour == 12 -> hour = 0
            else -> {}
        }

        targetDate.set(Calendar.HOUR_OF_DAY, hour)
        targetDate.set(Calendar.MINUTE, minute)
        targetDate.set(Calendar.SECOND, 0)
    }

    // Amount selection: prefer the number tied to a currency marker, otherwise the last
    // number in the transcript. This makes "2 coffees 150" parse as amount 150 with
    // comment "2 coffees" instead of picking the leading quantity "2".
    val amountMatches = AMOUNT_REGEX.findAll(working).toList()
    if (amountMatches.isEmpty()) return null
    val amountMatch = amountMatches.firstOrNull { it.hasCurrencyMarker() } ?: amountMatches.last()
    val amount = amountMatch.groupValues[2]

    // Remove just the digits (group 2), not the whole match, so the regex's \s*
    // between groups doesn't eat the surrounding spaces ("tea 20 now" -> "tea now").
    val amountRange = amountMatch.groups[2]?.range ?: amountMatch.range
    working = working.removeRange(amountRange).trim()

    var comment = working
        .replace(FILLER_WORDS_REGEX, "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    if (timeMatch == null) {
        if (
            targetDate.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
            targetDate.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        ) {
            targetDate.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
            targetDate.set(Calendar.MINUTE, now.get(Calendar.MINUTE))
            targetDate.set(Calendar.SECOND, now.get(Calendar.SECOND))
        } else {
            targetDate.set(Calendar.HOUR_OF_DAY, 0)
            targetDate.set(Calendar.MINUTE, 0)
            targetDate.set(Calendar.SECOND, 0)
        }
    }

    return VoiceInputResult(
        amount = amount,
        comment = comment,
        date = targetDate.time,
    )
}

// Splits a transcript into candidate record chunks. Strong separators (commas, semicolons,
// newlines) always split; the words "and"/"then" split only when both adjacent chunks carry a
// digit, so a comment like "bread and butter 50" stays one record while
// "tea 20 and lunch 150" becomes two.
private fun splitVoiceInput(input: String): List<String> {
    val chunks = STRONG_SEPARATOR_REGEX
        .split(input)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val records = mutableListOf<String>()
    for (chunk in chunks) {
        var last = 0
        var split = false
        for (match in WORD_SEPARATOR_REGEX.findAll(chunk)) {
            val left = chunk.substring(last, match.range.first).trim()
            val right = chunk.substring(match.range.last + 1).trim()
            if (left.any(Char::isDigit) && right.any(Char::isDigit)) {
                records.add(left)
                last = match.range.last + 1
                split = true
            }
        }
        if (split) {
            records.add(chunk.substring(last).trim())
        } else {
            records.add(chunk)
        }
    }
    return records
}

// Parses a transcript into zero or more spending records. Each chunk is parsed by the
// single-record parser; chunks without a usable number are dropped.
fun parseVoiceInputs(input: String): List<VoiceInputResult> =
    splitVoiceInput(input)
        .mapNotNull { parseVoiceInput(it) }
        .take(MAX_VOICE_RECORDS)

private const val MAX_VOICE_RECORDS = 12
