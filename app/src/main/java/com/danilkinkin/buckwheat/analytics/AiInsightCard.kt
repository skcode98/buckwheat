package com.danilkinkin.buckwheat.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.ai.AiInsightUiState
import com.danilkinkin.buckwheat.util.combineColors

// AI-generated analysis of the current budget period. Always renders (once the user opens the
// analytics) with a state-appropriate body: CTA to generate, loading progress, the report text,
// an error + retry, or the AI setup hint.
@Composable
fun AiInsightCard(
    modifier: Modifier = Modifier,
    state: AiInsightUiState,
    onGenerate: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = combineColors(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant,
                0.3f,
            ),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp, 16.dp)) {
            AiInsightHeader(
                trailing = {
                    when (state) {
                        is AiInsightUiState.Report ->
                            TextButton(onClick = onGenerate) {
                                Text(stringResource(R.string.ai_insight_regenerate))
                            }
                        is AiInsightUiState.Error ->
                            TextButton(onClick = onGenerate) {
                                Text(stringResource(R.string.ai_insight_retry))
                            }
                        else -> {}
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (state) {
                is AiInsightUiState.Idle -> {
                    Text(
                        text = stringResource(R.string.ai_insight_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onGenerate,
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
                is AiInsightUiState.Report -> {
                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is AiInsightUiState.Error -> {
                    Text(
                        text = state.message,
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
                        onClick = onOpenAiSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ai_insight_open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun AiInsightHeader(trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_analytics),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp).height(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ai_insight_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
