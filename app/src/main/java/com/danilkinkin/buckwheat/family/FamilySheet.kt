package com.danilkinkin.buckwheat.family

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.settings.FamilySyncViewModel
import com.danilkinkin.buckwheat.settings.SYNC_STALE_AFTER_MS
import com.danilkinkin.buckwheat.settings.SyncStatusChip
import com.danilkinkin.buckwheat.settings.SyncStatusViewModel
import com.danilkinkin.buckwheat.settings.syncStatus
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.prettyDate
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

const val FAMILY_SHEET = "family"
const val MEMBER_DETAIL_SHEET = "familyMemberDetail"

private val ZERO = BigDecimal.ZERO

private fun avatarColor(memberId: String): Color =
    Color.hsv((abs(memberId.hashCode()) % 360).toFloat(), 0.5f, 0.9f)

@Composable
fun FamilySheet(
    viewModel: FamilyViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    onClose: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val activeSession = session

    if (activeSession == null) {
        ConnectForm(onClose = onClose)
        return
    }

    FamilyEnrolledSheet(
        viewModel = viewModel,
        appViewModel = appViewModel,
        session = activeSession,
        onClose = onClose,
    )
}

@Composable
private fun FamilyEnrolledSheet(
    viewModel: FamilyViewModel,
    appViewModel: AppViewModel,
    session: com.danilkinkin.buckwheat.sync.FamilySession,
    onClose: () -> Unit,
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current

    val members by viewModel.members.collectAsStateWithLifecycle()
    val familyTotal by viewModel.familyTotal.collectAsStateWithLifecycle()
    val ownSpend by viewModel.ownSpend.collectAsStateWithLifecycle()
    val spendByMember by viewModel.spendByMember.collectAsStateWithLifecycle()
    val transactionCount by viewModel.transactionCount.collectAsStateWithLifecycle()
    val rosterLoading by viewModel.rosterLoading.collectAsStateWithLifecycle()
    val rosterFailed by viewModel.rosterFailed.collectAsStateWithLifecycle()
    val periodRange by viewModel.period.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    val spendsViewModel: SpendsViewModel = hiltViewModel()
    val currency by spendsViewModel.currency.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val days = periodRange.periodDays.coerceAtLeast(1)
    val averagePerDay = (spendByMember[session.memberId] ?: ZERO)
        .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)

    var showLeaveDialog by remember { mutableStateOf(false) }

    val activeMembers = members.filterNot { it.departed }
    val departedMembers = members.filter { it.departed }
    val sortedActive = activeMembers.sortedWith(
        compareBy<FamilyMember>({ it.id != session.memberId })
            .thenByDescending { spendByMember[it.id] ?: ZERO },
    )
    var showDeparted by remember { mutableStateOf(false) }

    val endCaption = buildString {
        append(prettyDate(periodRange.start, showTime = false, shortMonth = true))
        periodRange.endInclusive?.let { finish ->
            if (finish.time != periodRange.start.time) {
                append(" – ")
                append(prettyDate(finish, showTime = false, shortMonth = true))
            }
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
                SyncStatusBlock()
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                if (familyTotal == ZERO) {
                    Text(
                        text = stringResource(R.string.family_total_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        text = stringResource(R.string.family_nothing_spent),
                        style = MaterialTheme.typography.displaySmall,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.family_total_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        text = numberFormat(context, familyTotal, currency, trimDecimalPlaces = true),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.family_your_share, numberFormat(context, ownSpend, currency)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = endCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(20.dp))

            if (sortedActive.isEmpty() && !departedMembers.isEmpty() && !rosterLoading) {
                Text(
                    text = stringResource(R.string.family_solo_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                sortedActive.forEach { member ->
                    MemberRow(
                        member = member,
                        spend = spendByMember[member.id] ?: ZERO,
                        transactionCount = rows.count { it.memberId == member.id },
                        days = days,
                        isViewer = member.id == session.memberId,
                        currency = currency,
                        onClick = {
                            appViewModel.openSheet(PathState(MEMBER_DETAIL_SHEET, mapOf("memberId" to member.id)))
                        },
                    )
                }
            }

            if (rosterLoading && members.isEmpty()) {
                repeat(4) { SkeletonRow() }
            }

            if (rosterFailed && members.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.family_roster_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.family_retry))
                    }
                }
            }

            if (departedMembers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDeparted = !showDeparted }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.family_departed, departedMembers.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (showDeparted) "▲" else "▼")
                }
                if (showDeparted) {
                    departedMembers.forEach { member ->
                        MemberRow(
                            member = member,
                            spend = ZERO,
                            transactionCount = rows.count { it.memberId == member.id },
                            days = days,
                            isViewer = false,
                            currency = currency,
                            onClick = {},
                        )
                    }
                }
            }

SoloHintIfNeeded(
                joinCode = session.joinCode,
                activeMembers = sortedActive,
                departedMembers = departedMembers,
                rosterLoading = rosterLoading,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showLeaveDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.family_leave))
                }
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.family_disconnect))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.family_leave_confirm_title)) },
            text = { Text(stringResource(R.string.family_leave_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    viewModel.leave()
                }) {
                    Text(stringResource(R.string.family_leave))
                }
            },
        )
    }
}

@Composable
private fun SoloHintIfNeeded(
    joinCode: String,
    activeMembers: List<FamilyMember>,
    departedMembers: List<FamilyMember>,
    rosterLoading: Boolean,
) {
    if (rosterLoading) return
    if (activeMembers.isNotEmpty()) return
    if (departedMembers.isNotEmpty() && joinCode.isBlank()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (joinCode.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = joinCode,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.family_solo_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SyncStatusBlock() {
    val syncStatusViewModel: SyncStatusViewModel = hiltViewModel()
    val lastSyncedAt by syncStatusViewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val lastError by syncStatusViewModel.lastError.collectAsStateWithLifecycle()
    val syncing by syncStatusViewModel.syncing.collectAsStateWithLifecycle()
    val status = syncStatus(lastSyncedAt, lastError, System.currentTimeMillis(), SYNC_STALE_AFTER_MS)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SyncStatusChip(status = status, syncing = syncing)
    }
}

@Composable
private fun MemberRow(
    member: FamilyMember,
    spend: BigDecimal,
    transactionCount: Int,
    days: Long,
    isViewer: Boolean,
    currency: ExtendCurrency,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val averagePerDay = spend.divide(BigDecimal.valueOf(days.coerceAtLeast(1)), 2, RoundingMode.HALF_UP)

    val caption = buildList {
        add(stringResource(R.string.family_daily_average, numberFormat(context, averagePerDay, currency)))
        add(stringResource(R.string.family_transactions_count, transactionCount))
        if (!isViewer) add(stringResource(R.string.family_member_private_budget))
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(avatarColor(member.id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isViewer) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.family_you_badge).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = numberFormat(context, spend, currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun ConnectForm(
    onClose: () -> Unit,
) {
    val syncViewModel: FamilySyncViewModel = hiltViewModel()

    val serverUrl by syncViewModel.serverUrl.collectAsStateWithLifecycle()
    val displayName by syncViewModel.displayName.collectAsStateWithLifecycle()
    val inviteCode by syncViewModel.inviteCode.collectAsStateWithLifecycle()
    val busy by syncViewModel.busy.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        syncViewModel.messages.collect { message = it }
    }

    Surface(Modifier.padding(top = LocalBottomSheetScrollState.current.topPadding)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.family_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.family_not_connected_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = syncViewModel::onServerUrlChange,
                label = { Text(stringResource(R.string.family_server_url)) },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = syncViewModel::onDisplayNameChange,
                label = { Text(stringResource(R.string.family_display_name)) },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = inviteCode,
                onValueChange = syncViewModel::onInviteCodeChange,
                label = { Text(stringResource(R.string.family_join_code)) },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = syncViewModel::join,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.family_join))
                }
                Button(
                    onClick = syncViewModel::enrol,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.family_create))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Preview(name = "Member row")
@Composable
private fun MemberRowPreview() {
    BuckwheatTheme {
        MemberRow(
            member = FamilyMember("a", "Ren", departed = false, joinedAt = "2024-11-15"),
            spend = BigDecimal("22.50"),
            transactionCount = 4,
            days = 30,
            isViewer = true,
            currency = ExtendCurrency.none(),
            onClick = {},
        )
    }
}

@Preview(name = "Skeleton")
@Composable
private fun SkeletonPreview() {
    BuckwheatTheme {
        Column {
            SkeletonRow()
            SkeletonRow()
        }
    }
}