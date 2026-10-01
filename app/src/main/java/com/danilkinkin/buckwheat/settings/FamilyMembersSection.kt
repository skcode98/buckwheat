package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.ui.BuckwheatTheme

private const val AVATAR_SIZE_DP = 28
private const val UNKNOWN_INITIAL = "?"

/**
 * Who is in this family. Stateless on purpose: it takes the roster it is given, so the sheet decides
 * when a refresh happens and this only ever describes what is known right now.
 */
@Composable
fun FamilyMembersSection(
    members: List<FamilyMember>,
    currentMemberId: String?,
    loading: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onRefresh() }

    // Nothing known, nothing broken, nothing in flight: an empty section with a heading is just noise.
    if (members.isEmpty() && !loading && !failed) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.family_sync_members_title),
            style = MaterialTheme.typography.titleMedium,
        )

        if (loading) {
            Text(
                text = stringResource(R.string.family_sync_members_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        if (failed) {
            Text(
                text = stringResource(R.string.family_sync_members_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.family_sync_members_refresh))
            }
        }

        members.forEach { member ->
            FamilyMemberRow(
                member = member,
                isCurrentMember = member.id == currentMemberId,
            )
        }
    }
}

@Composable
private fun FamilyMemberRow(
    member: FamilyMember,
    isCurrentMember: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE_DP.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialOf(member.displayName),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName,
                style = MaterialTheme.typography.bodyLarge,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            if (member.isOwner || isCurrentMember) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (member.isOwner) {
                        Text(
                            text = stringResource(R.string.family_sync_member_owner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isCurrentMember) {
                        Text(
                            text = stringResource(R.string.family_sync_member_you),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A blank or whitespace-only name still needs a circle to render, and an empty `Text` would collapse
 * the avatar to nothing rather than showing a placeholder.
 */
private fun initialOf(displayName: String): String =
    displayName.trim().take(1).uppercase().takeIf { it.isNotBlank() } ?: UNKNOWN_INITIAL

@Preview
@Composable
private fun PreviewFamilyMembersSection() {
    BuckwheatTheme {
        FamilyMembersSection(
            members = listOf(
                FamilyMember("member-1", "Ada", isOwner = true, joinedAt = "2026-10-01T12:00:00Z"),
                FamilyMember("member-2", "Grace", isOwner = false, joinedAt = "2026-10-02T12:00:00Z"),
            ),
            currentMemberId = "member-2",
            loading = false,
            failed = false,
            onRefresh = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview
@Composable
private fun PreviewFamilyMembersSectionFailed() {
    BuckwheatTheme {
        FamilyMembersSection(
            members = emptyList(),
            currentMemberId = null,
            loading = false,
            failed = true,
            onRefresh = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}