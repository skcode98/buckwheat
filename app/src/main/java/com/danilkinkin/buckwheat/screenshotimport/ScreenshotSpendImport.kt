package com.danilkinkin.buckwheat.screenshotimport

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.callAi
import com.danilkinkin.buckwheat.keyboard.VoiceInputResult
import com.danilkinkin.buckwheat.keyboard.parseVoiceAiContents
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputs
import com.danilkinkin.buckwheat.util.parseAmountToBigDecimal
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Date
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SCREENSHOT_IMPORT_SHEET = "screenshotImport"

data class ScreenshotSpendDraft(
    val id: String = UUID.randomUUID().toString(),
    val amount: BigDecimal,
    val amountText: String,
    val comment: String,
    val date: Date,
)

data class ScreenshotParseOutcome(
    val drafts: List<ScreenshotSpendDraft>,
    val aiError: String? = null,
    val usedAi: Boolean = false,
)

suspend fun recognizeTextFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val image = InputImage.fromFilePath(context, uri)
    recognizeText(image)
}

suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
    val image = InputImage.fromBitmap(bitmap, 0)
    recognizeText(image)
}

private suspend fun recognizeText(image: InputImage): String = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
        .addOnSuccessListener { result ->
            cont.resume(result.text.orEmpty())
            recognizer.close()
        }
        .addOnFailureListener { e ->
            if (cont.isActive) cont.resumeWithException(e)
            recognizer.close()
        }
}

internal fun normalizeOcrText(raw: String): String =
    raw.replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

internal fun buildScreenshotSystemPrompt(): String =
    "You extract spending records from OCR text of a receipt, payment app screenshot, bank statement, or expense list. " +
        "Reply with ONLY a JSON array and nothing else, e.g. " +
        "[{\"amount\":\"150\",\"comment\":\"tea\",\"date\":\"today\"}," +
        "{\"amount\":\"45\",\"comment\":\"bus\",\"date\":\"2026-08-05\"}]. " +
        "Return one object per individual spend/expense record you can identify. " +
        "Ignore totals, subtotals, balances, taxes, discounts, and headings unless they are the only clear spend items. " +
        "amount must be a plain numeric string. comment must be a concise description or merchant/item name. " +
        "date must be ISO-8601, 'today', 'yesterday', 'tomorrow', or null when unknown."

internal fun buildScreenshotUserPrompt(ocrText: String): String =
    "OCR text:\n${ocrText.trim()}"

internal fun draftsFromVoiceResults(results: List<VoiceInputResult>): List<ScreenshotSpendDraft> =
    results.mapNotNull { result ->
        val amount = parseAmountToBigDecimal(result.amount) ?: return@mapNotNull null
        if (amount <= BigDecimal.ZERO) return@mapNotNull null
        ScreenshotSpendDraft(
            amount = amount,
            amountText = amount.stripTrailingZeros().toPlainString(),
            comment = result.comment.trim(),
            date = result.date,
        )
    }

suspend fun parseScreenshotSpendRecords(
    context: Context,
    ocrText: String,
): ScreenshotParseOutcome {
    val cleaned = normalizeOcrText(ocrText)
    if (cleaned.isBlank()) return ScreenshotParseOutcome(emptyList())

    return when (val ai = callAi(
        context = context,
        systemPrompt = buildScreenshotSystemPrompt(),
        userPrompt = buildScreenshotUserPrompt(cleaned),
    )) {
        is AiRouterResult.Success -> {
            val aiDrafts = draftsFromVoiceResults(parseVoiceAiContents(ai.text))
            if (aiDrafts.isNotEmpty()) {
                ScreenshotParseOutcome(drafts = aiDrafts, usedAi = true)
            } else {
                val fallback = draftsFromVoiceResults(parseVoiceInputs(cleaned))
                ScreenshotParseOutcome(
                    drafts = fallback,
                    aiError = "response contained no amount",
                    usedAi = false,
                )
            }
        }

        is AiRouterResult.Failure -> {
            val fallback = draftsFromVoiceResults(parseVoiceInputs(cleaned))
            ScreenshotParseOutcome(
                drafts = fallback,
                aiError = ai.message,
                usedAi = false,
            )
        }

        AiRouterResult.NotConfigured -> {
            val fallback = draftsFromVoiceResults(parseVoiceInputs(cleaned))
            ScreenshotParseOutcome(drafts = fallback, usedAi = false)
        }
    }
}
