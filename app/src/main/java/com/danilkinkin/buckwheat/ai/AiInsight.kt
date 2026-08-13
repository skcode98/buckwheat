package com.danilkinkin.buckwheat.ai

import android.content.Context
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_MODEL
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_PROVIDER_URL
import com.danilkinkin.buckwheat.di.aiIntelligenceEnabled
import com.danilkinkin.buckwheat.di.normalizeVoiceAiModel
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.extractModelContent
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.roundToDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000

// One category's share of the period spend, as fed to the AI report.
data class CategorySpendInsight(
    val name: String,
    val amount: BigDecimal,
    val percent: Int,
)

// Aggregated, anonymous view of the current budget period that the AI report is built from.
// No comments/identifiable data are sent — only totals, dates and category sums.
data class SpendInsightSummary(
    val currencyCode: String,
    val budget: BigDecimal,
    val spent: BigDecimal,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val today: LocalDate,
    val transactionCount: Int,
    val categories: List<CategorySpendInsight>,
    val biggestSpend: BigDecimal?,
    val biggestSpendComment: String?,
    val overspendDays: Int,
    val previousPeriodTotal: BigDecimal?,
    val dailyBudget: BigDecimal = BigDecimal.ZERO,
)

sealed class AiInsightResult {
    data class Success(val report: String) : AiInsightResult()
    data class Failure(val message: String) : AiInsightResult()
    object NotConfigured : AiInsightResult()
}

private val INSIGHT_DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy")

// Formats an amount the same way in the prompt and in tests: deterministic, locale-independent.
internal fun insightAmount(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

internal fun buildAiInsightSystemPrompt(): String =
    "You are a personal finance analyst for the Buckwheat budget app. Analyze the user's " +
        "spending for their budget period and reply with a concise, friendly analysis in the " +
        "language the user writes in. Structure your answer as: 1) one overview sentence on how " +
        "the period is going, 2) 3-5 bullet observations (start each line with '• '), calling out " +
        "category concentration, spending pace, overspending days and the biggest expense, " +
        "3) a short 'Watch out for' note only if something needs attention, and 4) one practical " +
        "tip. Keep it under 120 words. Do not use markdown headers or code blocks."

internal fun buildAiInsightUserPrompt(summary: SpendInsightSummary): String {
    val remaining = (summary.budget - summary.spent).coerceAtLeast(BigDecimal.ZERO)
    val elapsedDays = ChronoUnit.DAYS.between(summary.startDate, summary.today)
        .toInt()
        .coerceAtLeast(1)
    val average = if (summary.spent > BigDecimal.ZERO) {
        summary.spent.divide(elapsedDays.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
    } else {
        BigDecimal.ZERO
    }
    val categoryLines = if (summary.categories.isEmpty()) {
        "  none"
    } else {
        summary.categories.joinToString("\n") { category ->
            "  - ${category.name}: ${insightAmount(category.amount)} (${category.percent}%)"
        }
    }
    return buildString {
        append("Budget period: ${summary.startDate.format(INSIGHT_DATE_FORMAT)} to ")
        append("${summary.endDate.format(INSIGHT_DATE_FORMAT)}")
        append("\nToday: ${summary.today.format(INSIGHT_DATE_FORMAT)}")
        append("\nCurrency: ${summary.currencyCode.ifBlank { "(none)" }}")
        append("\nBudget: ${insightAmount(summary.budget)}")
        append("\nSpent so far: ${insightAmount(summary.spent)}")
        append("\nRemaining: ${insightAmount(remaining)}")
        append("\nElapsed: $elapsedDays days")
        append("\nAverage spend per day: ${insightAmount(average)}")
        append("\nTransactions: ${summary.transactionCount}")
        append("\nOverspending days: ${summary.overspendDays}")
        append(
            "\nPrevious period spent: ${
                summary.previousPeriodTotal?.let { insightAmount(it) } ?: "none"
            }"
        )
        append(
            "\nBiggest expense: ${
                if (summary.biggestSpendComment.isNullOrBlank()) {
                    summary.biggestSpend?.let { insightAmount(it) } ?: "none"
                } else {
                    "${summary.biggestSpendComment} (${summary.biggestSpend?.let { insightAmount(it) } ?: "0"})"
                }
            }"
        )
        append("\nCategory breakdown:\n")
        append(categoryLines)
    }
}

// The offline fallback engine: deterministically builds a report in the exact same shape as the
// AI output (overview line, "• " bullets, optional "Watch out for:" note and tip) purely from the
// period's data, so the monthly report always works without a network, an API key or the AI toggle.
// Pure so it is unit-testable.
internal fun buildOfflineReport(
    summary: SpendInsightSummary,
    spends: List<WindowSpend>,
): String {
    val spent = summary.spent
    val budget = summary.budget
    val zero = BigDecimal.ZERO
    if (spent <= zero) {
        return "No spending yet this period — once your first spend is in the report will " +
            "show your full month at a glance."
    }

    val elapsedDays = ChronoUnit.DAYS.between(summary.startDate, summary.today)
        .toInt()
        .coerceAtLeast(1)
    val average = spent.divide(elapsedDays.toBigDecimal(), 2, RoundingMode.HALF_EVEN)
    val percentUsed = if (budget > zero) {
        spent.multiply(BigDecimal(100))
            .divide(budget, 0, RoundingMode.HALF_EVEN)
            .toInt()
    } else {
        0
    }
    val remaining = (budget - spent).coerceAtLeast(zero)

    val lines = mutableListOf<String>()
    lines += when {
        budget > zero && spent > budget -> {
            "You've spent ${insightAmount(spent)} of a ${insightAmount(budget)} budget " +
                "($percentUsed%) and are over budget by ${insightAmount(spent - budget)}."
        }
        budget > zero && percentUsed >= 80 -> {
            "You've spent ${insightAmount(spent)} of a ${insightAmount(budget)} budget " +
                "($percentUsed%) — running low, with ${insightAmount(remaining)} left."
        }
        budget > zero -> {
            "You've spent ${insightAmount(spent)} of a ${insightAmount(budget)} budget " +
                "($percentUsed%), leaving ${insightAmount(remaining)}."
        }
        else -> "You've spent ${insightAmount(spent)} so far across ${summary.transactionCount} transactions."
    }

    lines += ""
    val bullets = mutableListOf<String>()

    summary.categories.maxByOrNull { it.amount }?.let { top ->
        bullets += "• ${top.name} is your biggest category at ${insightAmount(top.amount)} (${top.percent}%)."
    }

    if (summary.dailyBudget > zero) {
        if (average > summary.dailyBudget) {
            bullets += "• You're spending ${insightAmount(average)}/day, above your " +
                "${insightAmount(summary.dailyBudget)} daily budget."
        } else {
            bullets += "• You're spending ${insightAmount(average)}/day, within your " +
                "${insightAmount(summary.dailyBudget)} daily budget."
        }
    } else {
        bullets += "• That's ${insightAmount(average)}/day on average."
    }

    if (summary.overspendDays > 0) {
        val dayWord = if (summary.overspendDays == 1) "day" else "days"
        bullets += "• ${summary.overspendDays} $dayWord exceeded the daily budget."
    }

    val biggest = summary.biggestSpend
    if (biggest != null && biggest > zero) {
        val label = summary.biggestSpendComment?.takeIf { it.isNotBlank() } ?: "single expense"
        bullets += "• The biggest expense was ${insightAmount(biggest)} ($label)."
    }

    summary.previousPeriodTotal?.let { previous ->
        if (previous > zero) {
            val delta = spent.subtract(previous)
                .divide(previous, 2, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal(100))
            val direction = when {
                delta.signum() > 0 -> "up"
                delta.signum() < 0 -> "down"
                else -> "unchanged"
            }
            val rounded = delta.abs().setScale(0, RoundingMode.HALF_UP).toInt()
            bullets += "• That's $direction $rounded% versus the previous period."
        }
    }

    val peakDay = spends
        .groupBy { roundToDay(it.date) }
        .maxByOrNull { (_, daySpends) ->
            daySpends.fold(zero) { acc, spend -> acc + spend.value }
        }
    if (peakDay != null) {
        val peakAmount = peakDay.value.fold(zero) { acc, spend -> acc + spend.value }
        val peakLabel = peakDay.key.toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(INSIGHT_DATE_FORMAT)
        bullets += "• Your peak spending day was $peakLabel at ${insightAmount(peakAmount)}."
    }

    lines += bullets

    if (budget > zero && spent > budget) {
        lines += ""
        lines += "Watch out for: you've crossed the budget — tighten spending for the rest of the period."
    } else if (budget > zero && percentUsed >= 80) {
        lines += ""
        lines += "Watch out for: the budget is nearly gone — make the remaining ${insightAmount(remaining)} last."
    }

    if (summary.categories.isNotEmpty()) {
        lines += ""
        lines += "Tip: a small trim in your biggest category usually stretches the budget furthest."
    }

    return lines.joinToString("\n")
}

// Cleans the model's report for display: strips markdown code fences, an echoed "Report:" label
// and excess blank lines. Pure so it is unit-testable.
internal fun parseAiInsightReport(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    var text = trimmed
    text = MARKDOWN_FENCE.replace(text, "").trim()
    text = text.replaceFirst(Regex("^[Rr]eport:?\\s*"), "").trim()
    text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
    return text
}

private val MARKDOWN_FENCE = Regex("```[a-zA-Z]*\\s*")

// Counts distinct days (within [start, end], capped at today) whose total spend exceeded the
// daily budget. Zero/negative daily budgets disable the metric.
internal fun overspendDayCount(
    spends: List<WindowSpend>,
    start: LocalDate,
    finish: LocalDate,
    today: LocalDate,
    dailyBudget: BigDecimal,
): Int {
    if (dailyBudget <= BigDecimal.ZERO) return 0
    val end = minOf(finish, today)
    if (end.isBefore(start)) return 0
    return spends
        .mapNotNull { spend ->
            spend.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                .takeIf { day -> !day.isBefore(start) && !day.isAfter(end) }
                ?.let { day -> day to spend.value }
        }
        .groupBy({ it.first }, { it.second })
        .count { (_, values) -> values.fold(BigDecimal.ZERO) { acc, value -> acc + value } > dailyBudget }
}

// Generates an AI spending analysis for the current period. Network access is bounded (10s
// connect / 20s read), the socket is always disconnected, and failures carry a human-readable
// reason. When the AI Intelligence master toggle is off or no API key is saved the caller sees
// NotConfigured and renders the setup hint instead.
suspend fun generateAiInsight(
    context: Context,
    summary: SpendInsightSummary,
): AiInsightResult = withContext(Dispatchers.IO) {
    val prefs = context.settingsDataStore.data.first()
    val apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
    if (!aiIntelligenceEnabled(prefs) || apiKey.isBlank()) {
        return@withContext AiInsightResult.NotConfigured
    }

    val providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty().ifBlank {
        DEFAULT_VOICE_AI_PROVIDER_URL
    }
    val model = normalizeVoiceAiModel(prefs[voiceAiModelStoreKey])
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_VOICE_AI_MODEL

    var connection: HttpURLConnection? = null
    try {
        val requestBody = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", buildAiInsightSystemPrompt())
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildAiInsightUserPrompt(summary))
                    )
            )
            .toString()

        val url = URL(providerUrl)
        val conn = url.openConnection() as HttpURLConnection
        connection = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(requestBody)
            writer.flush()
        }

        if (conn.responseCode !in 200..299) {
            val errorBody = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().trim().take(200)
            val suffix = if (errorBody.isNotEmpty()) " — $errorBody" else ""
            return@withContext AiInsightResult.Failure("HTTP ${conn.responseCode}$suffix")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val report = parseAiInsightReport(extractModelContent(responseText))
        if (report.isEmpty()) {
            return@withContext AiInsightResult.Failure("response contained no analysis")
        }
        AiInsightResult.Success(report)
    } catch (e: Exception) {
        AiInsightResult.Failure(e.message ?: e.javaClass.simpleName)
    } finally {
        connection?.disconnect()
    }
}
