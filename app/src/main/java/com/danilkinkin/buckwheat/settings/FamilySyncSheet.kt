package com.danilkinkin.buckwheat.settings

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.DescriptionButton
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.errorForReport
import java.time.Instant
import java.util.Date

const val FAMILY_SYNC_SHEET = "familySync"

@Composable
fun FamilySyncSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    viewModel: FamilySyncViewModel = hiltViewModel(),
    syncStatusViewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val session by viewModel.session.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val inviteCode by viewModel.inviteCode.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val mintedInvite by viewModel.mintedInvite.collectAsStateWithLifecycle()
    val memberName by viewModel.memberName.collectAsStateWithLifecycle()
    val mintedInviteExpiresAt by viewModel.mintedInviteExpiresAt.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val membersLoading by viewModel.membersLoading.collectAsStateWithLifecycle()
    val membersFailed by viewModel.membersFailed.collectAsStateWithLifecycle()

    // Collected, not snapshotted. `status()` reads the three values once at composition, so the chip,
    // the last-synced line and the error line could never change while the sheet was open -- tapping
    // Sync looked like it had done nothing even when it had succeeded. The ViewModel exposes these as
    // flows for exactly this reason; the worker writes them when it finishes.
    val lastSyncedAt by syncStatusViewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val lastError by syncStatusViewModel.lastError.collectAsStateWithLifecycle()
    val pendingCount by syncStatusViewModel.pendingCount.collectAsStateWithLifecycle()
    val syncing by syncStatusViewModel.syncing.collectAsStateWithLifecycle()

    val lastSyncedLabel = remember(lastSyncedAt) {
        if (lastSyncedAt == 0L) {
            null
        } else {
            DateUtils.getRelativeTimeSpanString(
                lastSyncedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        }
    }
    val onSyncNow = {
        syncStatusViewModel.syncNow()
        syncStatusViewModel.refreshPendingCount()
    }
    LaunchedEffect(Unit) { syncStatusViewModel.refreshPendingCount() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> appViewModel.showSnackbar(message) }
    }

    val expiresAtLabel = remember(mintedInviteExpiresAt, context) {
        mintedInviteExpiresAt?.let { inviteExpiryLabel(context, it) }
    }
    val inviteShareLine = mintedInvite?.let { code ->
        stringResource(R.string.family_sync_invite_share_text, code)
    }
    val inviteShareText = if (expiresAtLabel == null) {
        inviteShareLine.orEmpty()
    } else {
        "${inviteShareLine.orEmpty()}\n\n$expiresAtLabel"
    }
    val copyLabel = stringResource(R.string.family_sync_invite_copy)
    val shareLabel = stringResource(R.string.family_sync_invite_share)
    val copiedText = stringResource(R.string.family_sync_invite_copied)

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.family_sync_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navigationBarHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = session
                if (current == null) {
                    Text(
                        text = stringResource(R.string.family_sync_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    // Consent has to be offered before the person commits, not surfaced once they are
                    // already enrolled. The same notice repeats in the connected branch, where it
                    // reads as a standing reminder rather than a one-off warning.
                    Text(
                        text = stringResource(R.string.family_sync_transparency_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = viewModel::onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_server_url)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                        ),
                    )

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = viewModel::onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_display_name)) },
                        singleLine = true,
                        enabled = !busy,
                    )

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_create)) },
                        description = {
                            Text(stringResource(R.string.family_sync_create_description))
                        },
                        onClick = viewModel::enrol,
                        enabled = !busy,
                    )

                    Text(
                        text = stringResource(R.string.family_sync_or_join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = viewModel::onInviteCodeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.family_sync_invite_code)) },
                        singleLine = true,
                        enabled = !busy,
                    )

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_join)) },
                        description = {
                            Text(stringResource(R.string.family_sync_join_description))
                        },
                        onClick = viewModel::join,
                        enabled = !busy,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.family_sync_connected),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    val shownName = memberName.ifBlank { displayName }
                    if (shownName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.family_sync_member_name, shownName),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.family_sync_family, current.familyId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = stringResource(R.string.family_sync_member, current.memberId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = stringResource(R.string.family_sync_ids_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }

                    Text(
                        text = stringResource(R.string.family_sync_transparency_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncStatusChip(
                            syncStatus(lastSyncedAt, lastError, System.currentTimeMillis(), SYNC_STALE_AFTER_MS),
                            syncing = syncing,
                        )
                    }


                    Text(
                        text = lastSyncedLabel?.let {
                            stringResource(R.string.family_sync_last_synced_at, it)
                        } ?: stringResource(R.string.family_sync_last_synced_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    lastError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (pendingCount > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.family_sync_pending,
                                pendingCount,
                                pendingCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_sync_now)) },
                        onClick = onSyncNow,
                        enabled = !busy,
                    )

                    FamilyMembersSection(
                        members = members,
                        currentMemberId = current.memberId,
                        loading = membersLoading,
                        failed = membersFailed,
                        onRefresh = viewModel::refreshMembers,
                    )

                    mintedInvite?.let { code ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(),
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.family_sync_invite_created, code),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (expiresAtLabel != null) {
                                    Text(
                                        text = expiresAtLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(code))
                                        appViewModel.showSnackbar(copiedText)
                                    }) {
                                        Text(copyLabel)
                                    }
                                    TextButton(onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, inviteShareText)
                                        }
                                        try {
                                            context.startActivity(
                                                Intent.createChooser(intent, shareLabel)
                                            )
                                        } catch (e: Exception) {
                                            context.errorForReport = e.stackTraceToString()
                                        }
                                    }) {
                                        Text(shareLabel)
                                    }
                                }
                            }
                        }
                        DescriptionButton(
                            title = { Text(stringResource(R.string.family_sync_dismiss_invite)) },
                            onClick = viewModel::clearMintedInvite,
                            enabled = !busy,
                        )
                    }

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_create_invite)) },
                        description = {
                            Text(stringResource(R.string.family_sync_create_invite_description))
                        },
                        onClick = viewModel::mintInvite,
                        enabled = !busy,
                    )

                    Spacer(Modifier.height(8.dp))

                    DescriptionButton(
                        title = { Text(stringResource(R.string.family_sync_sign_out)) },
                        description = {
                            Text(stringResource(R.string.family_sync_sign_out_description))
                        },
                        onClick = viewModel::signOut,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

private fun inviteExpiryLabel(context: Context, expiresAt: String): String? = runCatching {
    val instant = Instant.parse(expiresAt)
    if (instant.isAfter(Instant.now())) {
        context.getString(
            R.string.family_sync_invite_expires_at,
            DateFormat.getTimeFormat(context).format(Date.from(instant)),
        )
    } else {
        context.getString(R.string.family_sync_invite_expired)
    }
}.getOrNull()