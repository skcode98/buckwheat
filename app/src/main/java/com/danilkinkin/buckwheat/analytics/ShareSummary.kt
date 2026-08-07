package com.danilkinkin.buckwheat.analytics

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryTotals
import com.danilkinkin.buckwheat.errorForReport
import com.danilkinkin.buckwheat.util.countDays
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Date

// One line of the category breakdown in the shared text summary.
data class ShareCategoryLine(val label: String, val amount: BigDecimal)

// Localized labels used by the summary text. Kept as a plain holder so the text builder is
// pure (no Context) and trivially unit-testable.
data class ShareSummaryLabels(
    val budget: String,
    val spent: String,
    val remaining: String,
    val dailyAverage: String,
    val transactions: String,
    val categories: String,
)

// Builds the plain-text period summary shared by the Analytics screen. Pure so it is
// unit-testable; the composable resolves localized labels and supplies the money formatter.
fun buildShareSummary(
    periodLabel: String,
    budget: BigDecimal,
    spent: BigDecimal,
    dailyAverage: BigDecimal,
    transactionsCount: Int,
    categories: List<ShareCategoryLine>,
    labels: ShareSummaryLabels,
    formatMoney: (BigDecimal) -> String,
): String {
    val remaining = (budget - spent).coerceAtLeast(BigDecimal.ZERO)
    return buildString {
        appendLine(periodLabel)
        appendLine()
        appendLine("${labels.budget}: ${formatMoney(budget)}")
        appendLine("${labels.spent}: ${formatMoney(spent)}")
        appendLine("${labels.remaining}: ${formatMoney(remaining)}")
        appendLine("${labels.dailyAverage}: ${formatMoney(dailyAverage)}")
        appendLine("${labels.transactions}: $transactionsCount")
        if (categories.isNotEmpty()) {
            appendLine()
            appendLine("${labels.categories}:")
            categories.forEach { appendLine("• ${it.label} — ${formatMoney(it.amount)}") }
        }
    }.trimEnd()
}

// Launcher for sharing the current period's summary as text via the system share sheet.
@Composable
fun rememberShareSummary(
    appViewModel: AppViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    categoryEmojis: Map<String, String> = emptyMap(),
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val labels = ShareSummaryLabels(
        budget = stringResource(R.string.share_summary_budget),
        spent = stringResource(R.string.share_summary_spent),
        remaining = stringResource(R.string.share_summary_remaining),
        dailyAverage = stringResource(R.string.share_summary_daily_average),
        transactions = stringResource(R.string.share_summary_transactions),
        categories = stringResource(R.string.share_summary_categories),
    )
    val subject = stringResource(R.string.share_summary_title)
    val chooserTitle = stringResource(R.string.share_summary_chooser)
    val failedText = stringResource(R.string.share_summary_failed)

    return {
        try {
            val spends = spendsViewModel.periodSpends.value ?: emptyList()
            val budget = spendsViewModel.budget.value ?: BigDecimal.ZERO
            val spent = spendsViewModel.spent.value ?: BigDecimal.ZERO
            val startDate = spendsViewModel.startPeriodDate.value ?: Date()
            val finishDate = spendsViewModel.finishPeriodDate.value ?: Date()
            val currency = spendsViewModel.currency.value ?: ExtendCurrency.none()

            val days = countDays(minOf(Date(), finishDate), startDate).coerceAtLeast(1)
            val dailyAverage = spent.divide(BigDecimal(days), 2, RoundingMode.HALF_UP)

            val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val periodLabel = "${dateFormatter.format(startDate.toLocalDate())} — " +
                dateFormatter.format(finishDate.toLocalDate())

            val categories = categoryTotals(spends).map { (key, total) ->
                val label = when (key) {
                    is CategoryKey.BuiltIn ->
                        "${key.category.emoji} ${context.getString(key.category.labelRes)}"
                    is CategoryKey.Custom ->
                        "${SpendCategory.emojiFor(key.name, categoryEmojis[key.name])} ${key.name}"
                }
                ShareCategoryLine(label, total)
            }

            val text = buildShareSummary(
                periodLabel = periodLabel,
                budget = budget,
                spent = spent,
                dailyAverage = dailyAverage,
                transactionsCount = spends.size,
                categories = categories,
                labels = labels,
                formatMoney = { numberFormat(context, it, currency) },
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
        } catch (e: Exception) {
            context.errorForReport = e.stackTraceToString()
            coroutineScope.launch {
                appViewModel.showSnackbar(failedText)
            }
        }
    }
}
