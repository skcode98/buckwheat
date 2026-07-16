package com.danilkinkin.buckwheat.keyboard

import java.util.Calendar
import java.util.Date
import java.util.Locale

data class VoiceInputResult(
    val amount: String,
    val comment: String,
    val date: Date,
)

fun parseVoiceInput(input: String): VoiceInputResult? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    val amountMatch = Regex(
        """(\d+(?:[.,]\d+)?)\s*(?:rs|rupees?|rupay|₹)?""",
        RegexOption.IGNORE_CASE,
    ).find(trimmed) ?: return null

    val amount = amountMatch.groupValues[1].replace(",", ".")

    var textWithoutAmount = trimmed
        .replaceFirst(amountMatch.value, "")
        .trim()

    val now = Calendar.getInstance()
    val targetDate = Calendar.getInstance()

    val lower = textWithoutAmount.lowercase(Locale.getDefault())

    val isYesterday = lower.contains("yesterday") || lower.contains("kal")
    val isTomorrow = lower.contains("tomorrow")

    if (isYesterday) {
        targetDate.add(Calendar.DAY_OF_YEAR, -1)
    }
    if (isTomorrow) {
        targetDate.add(Calendar.DAY_OF_YEAR, 1)
    }

    val timeMatch = Regex(
        """(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""",
        RegexOption.IGNORE_CASE,
    ).find(textWithoutAmount)

    if (timeMatch != null) {
        var hour = timeMatch.groupValues[1].toInt()
        val minute =
            timeMatch.groupValues[2].let { if (it.isEmpty()) 0 else it.toInt() }
        val ampm = timeMatch.groupValues[3].lowercase()

        when {
            ampm.startsWith("pm") && hour < 12 -> hour += 12
            ampm.startsWith("am") && hour == 12 -> hour = 0
            else -> {}
        }

        targetDate.set(Calendar.HOUR_OF_DAY, hour)
        targetDate.set(Calendar.MINUTE, minute)
        targetDate.set(Calendar.SECOND, 0)
    } else {
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

    var comment = textWithoutAmount
    if (timeMatch != null) {
        comment = comment.replace(timeMatch.value, "")
    }

    comment = comment.replace(
        Regex(
            """\b(now|today|yesterday|kal|tomorrow|rs|rupees?|rupay|at|am|pm|a\.m\.|p\.m\.)\b""",
            RegexOption.IGNORE_CASE,
        ),
        "",
    )
    comment = comment.replace(Regex("""\s+"""), " ").trim()
    comment = comment.replaceFirst(amountMatch.value, "").trim()

    return VoiceInputResult(
        amount = amount,
        comment = comment,
        date = targetDate.time,
    )
}
