package com.danilkinkin.buckwheat.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.DescriptionButton
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.sync.ConflictNotice
import com.danilkinkin.buckwheat.sync.ConflictReason

const val SYNC_CONFLICTS_SHEET = "syncConflicts"

@Composable
fun SyncConflictsSheet(
    viewModel: SyncConflictsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()

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
                    text = stringResource(R.string.sync_conflicts_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (conflicts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.sync_conflicts_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = navigationBarHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(conflicts, key = { "${it.table}/${it.id}" }) { conflict ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(conflict.table.labelRes()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = conflict.winnerText(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            Text(
                                text = stringResource(conflict.reason.labelRes()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    DescriptionButton(
                        modifier = Modifier.fillMaxWidth(),
                        title = { Text(stringResource(R.string.sync_conflicts_dismiss_all)) },
                        onClick = viewModel::dismiss,
                    )
                }
            }
        }
    }
}

@StringRes
private fun String.labelRes(): Int = when (this) {
    "transactions" -> R.string.sync_conflicts_table_transactions
    "archived_transactions" -> R.string.sync_conflicts_table_archived_transactions
    "budget_periods" -> R.string.sync_conflicts_table_budget_periods
    "saved_categories" -> R.string.sync_conflicts_table_saved_categories
    "saved_tags" -> R.string.sync_conflicts_table_saved_tags
    "recurring_templates" -> R.string.sync_conflicts_table_recurring_templates
    "savings_goals" -> R.string.sync_conflicts_table_savings_goals
    else -> R.string.sync_conflicts_table_other
}

/**
 * The memberless tables are shared by the family, so their rows have no member id and the server omits
 * `wonByMemberId`. Saying "shared by the family" is the truth; printing an empty line, or the four
 * character string "null", is not.
 */
@Composable
private fun ConflictNotice.winnerText(): String = wonByMemberId
    ?.takeIf { it.isNotBlank() }
    ?.let { stringResource(R.string.sync_conflicts_won_by, it) }
    ?: stringResource(R.string.sync_conflicts_shared_by_family)

@StringRes
private fun ConflictReason.labelRes(): Int = when (this) {
    ConflictReason.STALE_VERSION -> R.string.sync_conflicts_reason_stale_version
    ConflictReason.DELETED_REMOTELY -> R.string.sync_conflicts_reason_deleted_remotely
    ConflictReason.CROSS_FAMILY_WRITE -> R.string.sync_conflicts_reason_cross_family_write
}
