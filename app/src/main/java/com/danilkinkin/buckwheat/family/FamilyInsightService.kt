package com.danilkinkin.buckwheat.family

import android.content.Context
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.callAi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A family summary, whether or not a model wrote it.
 *
 * [fromModel] is the honest part. The offline text is not a degraded error state shown as if it were
 * an answer; the screen says which one it is showing, because a person told their household is doing
 * badly deserves to know whether a model said it.
 */
data class FamilySummary(
    val text: String,
    val fromModel: Boolean,
)

/**
 * Produces the family summary.
 *
 * Reuses [callAi] rather than adding a provider, a model field or an API key: the settings
 * screen stays the single place AI is configured, the same `aiIntelligenceEnabled` master switch
 * applies, and the same retry and timeout behaviour is inherited. A second AI configuration would be
 * a second thing to get wrong about privacy.
 *
 * The offline renderer always runs and is returned when there is no key, no network, or AI is off, so
 * the family screen shows real content in every case. Same arrangement as the monthly report, and for
 * the same reason.
 */
@Singleton
class FamilyInsightService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun summarise(snapshot: PseudonymousFamilySnapshot, familyAiEnabled: Boolean): FamilySummary {
        val offline = FamilySummary(offlineFamilySummary(snapshot), fromModel = false)
        if (!familyAiEnabled) return offline

        return when (
            val result = callAi(
                context = context,
                systemPrompt = "You summarise household spending. You never guess who anyone is.",
                userPrompt = familySummaryPrompt(snapshot),
            )
        ) {
            is AiRouterResult.Success -> FamilySummary(result.text, fromModel = true)
            // NotConfigured and Failure are the same thing to a person standing on a platform with no
            // signal: here is the summary. Swallowing the reason is deliberate, and the reason belongs
            // in settings rather than in front of someone reading their budget.
            else -> offline
        }
    }
}
