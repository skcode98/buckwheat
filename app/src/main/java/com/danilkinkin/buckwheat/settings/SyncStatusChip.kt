package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.ui.colorBad
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorNotGood

enum class SyncStatusKind {
    /** This device has never reached the server: there is no last run to describe. */
    NEVER,

    /** The last run applied and is recent enough to still be worth trusting. */
    SYNCED,

    /** The last run worked, but too long ago that the family may have moved on without this device. */
    STALE,

    /** The last run failed. The reason is kept, because "it failed" and "it failed because..." are not the same message. */
    FAILED,
}

data class SyncStatus(
    val kind: SyncStatusKind,
    val lastSyncedAt: Long,
    val lastError: String?,
)

/**
 * Pure on purpose: everything that decides what the chip says lives here, not in the composable, so it
 * can be tested without Android and reused by any surface that wants to show the same state.
 *
 * The order of the tests is the whole point. A failure outranks a recent tick, because a run that
 * failed says less about the family's data than it does about this device, and a chip reading "Synced"
 * over a stored error is precisely the false reassurance this state exists to prevent. Only then does
 * "never" outrank "stale": a device that has never synced is not out of date, it is uninformed.
 */
fun syncStatus(lastSyncedAt: Long, lastError: String?, now: Long, staleAfterMs: Long): SyncStatus =
    SyncStatus(
        kind = when {
            !lastError.isNullOrBlank() -> SyncStatusKind.FAILED
            lastSyncedAt == 0L -> SyncStatusKind.NEVER
            now - lastSyncedAt > staleAfterMs -> SyncStatusKind.STALE
            else -> SyncStatusKind.SYNCED
        },
        lastSyncedAt = lastSyncedAt,
        lastError = lastError,
    )

/**
 * Sized for [com.danilkinkin.buckwheat.base.TextRow]'s `endContent` slot, which is a fixed 24dp-high
 * row, so the height is required rather than merely preferred: a chip that grew would be clipped.
 */
@Composable
fun SyncStatusChip(
    status: SyncStatus,
    syncing: Boolean = false,
    modifier: Modifier = Modifier,
) {
val label = when {
        syncing -> stringResource(R.string.family_sync_status_syncing)
        else -> when (status.kind) {
            SyncStatusKind.FAILED -> stringResource(R.string.family_sync_status_failed)
            SyncStatusKind.STALE -> stringResource(R.string.family_sync_status_stale)
            SyncStatusKind.SYNCED -> stringResource(R.string.family_sync_status_synced)
            SyncStatusKind.NEVER -> stringResource(R.string.family_sync_status_never)
        }
    }
    val container = when (status.kind) {
        SyncStatusKind.FAILED -> colorBad
        SyncStatusKind.STALE -> colorNotGood
        SyncStatusKind.SYNCED -> colorGood
        // Muted rather than coloured: there is nothing wrong, there is just nothing yet.
        SyncStatusKind.NEVER -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val content = when (status.kind) {
        SyncStatusKind.FAILED -> MaterialTheme.colorScheme.surface
        SyncStatusKind.STALE -> MaterialTheme.colorScheme.surface
        SyncStatusKind.SYNCED -> MaterialTheme.colorScheme.surface
        // The other containers are saturated and the label sits on top of them. This one is a 12%
        // wash, so surface-coloured text on it would be unreadable in a light theme.
        SyncStatusKind.NEVER -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    Surface(
        modifier = modifier.requiredHeight(24.dp),
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                modifier = Modifier.padding(12.dp, 0.dp),
                text = label,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
