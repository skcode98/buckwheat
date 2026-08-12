package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.ai.AiInsightUiState
import com.danilkinkin.buckwheat.ai.AiInsightViewModel
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState

const val AI_INSIGHT_SHEET = "aiInsight"

// AI-generated analysis of the current budget period, reached from Settings. State-driven:
// CTA to generate, loading progress, the report (bullets rendered as rows), an error + retry,
// or the AI setup hint.
@Composable
fun AiInsightSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    aiInsightViewModel: AiInsightViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val state by aiInsightViewModel.state.observeAsState(AiInsightUiState.Idle)
    val currentState = state

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.ai_insight_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = navigationBarHeight)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ai_insight_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    when (currentState) {
                        is AiInsightUiState.Report ->
                            TextButton(onClick = { aiInsightViewModel.generate() }) {
                                Text(stringResource(R.string.ai_insight_regenerate))
                            }
                        is AiInsightUiState.Error ->
                            TextButton(onClick = { aiInsightViewModel.generate() }) {
                                Text(stringResource(R.string.ai_insight_retry))
                            }
                        else -> {}
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (currentState) {
                    is AiInsightUiState.Idle -> {
                        Button(
                            onClick = { aiInsightViewModel.generate() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_insight_generate))
                        }
                    }
                    is AiInsightUiState.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.ai_insight_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AiInsightUiState.Report -> ReportBody(currentState.text)
                    is AiInsightUiState.Error -> {
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    is AiInsightUiState.NotConfigured -> {
                        Text(
                            text = stringResource(R.string.ai_insight_not_configured_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                appViewModel.openSheet(PathState(VOICE_AI_SETTINGS_SHEET))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_insight_open_settings))
                        }
                    }
                }
            }
        }
    }
}

// Renders the AI report line by line: "• " bullet lines become bullet rows, everything else a
// paragraph. Plain text would read like a wall of text; this keeps the report scannable.
@Composable
private fun ReportBody(text: String) {
    val lines = remember(text) { text.lines() }
    Column(Modifier.fillMaxWidth()) {
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(6.dp))
                line.startsWith("•") -> {
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = line.removePrefix("•").trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                else -> Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
