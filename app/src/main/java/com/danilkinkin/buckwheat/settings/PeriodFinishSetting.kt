package com.danilkinkin.buckwheat.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.di.finishPeriodDateStoreKey
import com.danilkinkin.buckwheat.di.periodFinishEnabledStoreKey
import com.danilkinkin.buckwheat.notifications.PeriodFinishScheduler
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.combineColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

// Settings switch for the end-of-period summary notification: posts once when the current
// budget period reaches its finish date. Opt-in and gated on the POST_NOTIFICATIONS permission.
@Composable
fun PeriodFinishSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        enabled = prefs[periodFinishEnabledStoreKey] ?: false
    }

    fun scheduleForCurrentPeriod() {
        coroutineScope.launch {
            val budgetPrefs = context.budgetDataStore.data.first()
            val finishDate = budgetPrefs[finishPeriodDateStoreKey] ?: return@launch
            PeriodFinishScheduler.schedule(context, Date(finishDate))
        }
    }

    fun enable() {
        enabled = true
        coroutineScope.launch {
            context.settingsDataStore.edit { it[periodFinishEnabledStoreKey] = true }
        }
        scheduleForCurrentPeriod()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enable()
        }
    }

    fun onToggle() {
        if (enabled) {
            enabled = false
            PeriodFinishScheduler.cancel(context)
            coroutineScope.launch {
                context.settingsDataStore.edit { it[periodFinishEnabledStoreKey] = false }
            }
        } else {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                enable()
            }
        }
    }

    val iconTint = contentColorFor(
        combineColors(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.surfaceVariant,
            angle = 0.3F,
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_calendar),
                tint = iconTint,
                contentDescription = null,
            )
        }
        Text(
            text = stringResource(R.string.period_finish_setting_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
        )
    }
}
