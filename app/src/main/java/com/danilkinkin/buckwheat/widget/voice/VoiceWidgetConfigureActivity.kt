package com.danilkinkin.buckwheat.widget.voice

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.base.CheckedRow
import com.danilkinkin.buckwheat.di.voiceWidgetDesignStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Launched by the launcher when a voice widget is placed (android:configure on the
// VoiceWidgetReceiver). Picks the design for THIS widget instance only: the choice is stored
// per-instance in the widget's own Glance state and overrides the global "Voice widget design"
// setting, so several placed voice widgets can each keep their own design. "Follow global"
// clears the override and keeps the instance linked to the Settings choice.
class VoiceWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            BuckwheatTheme {
                VoiceWidgetConfigureScreen(
                    onApply = { design ->
                        lifecycleScope.launch {
                            updateAppWidgetState(applicationContext, AppWidgetId(appWidgetId)) {
                                it[WidgetReceiver.voiceDesignOverridePreferenceKey] =
                                    design?.name ?: ""
                            }
                            WidgetReceiver.requestUpdateData(
                                applicationContext,
                                VoiceWidgetReceiver::class.java,
                            )
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VoiceWidgetConfigureScreen(
    onApply: (VoiceWidgetDesign?) -> Unit,
) {
    val context = LocalContext.current
    // null = "follow global" (no per-instance override).
    var selected by remember { mutableStateOf<VoiceWidgetDesign?>(null) }

    LaunchedEffect(Unit) {
        val saved = context.settingsDataStore.data.first()[voiceWidgetDesignStoreKey]
        selected = runCatching {
            VoiceWidgetDesign.valueOf(saved ?: "")
        }.getOrDefault(VoiceWidgetDesign.PERCENT)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.voice_widget_configure_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.voice_widget_configure_hint),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(Modifier.height(16.dp))
            CheckedRow(
                checked = selected == null,
                onValueChange = { selected = null },
                text = stringResource(R.string.voice_widget_configure_default),
                description = stringResource(R.string.voice_widget_configure_default_desc),
            )
            VoiceWidgetDesign.entries.forEach { option ->
                CheckedRow(
                    checked = selected == option,
                    onValueChange = { selected = option },
                    text = stringResource(
                        when (option) {
                            VoiceWidgetDesign.PERCENT -> R.string.voice_widget_design_percent
                            VoiceWidgetDesign.AMOUNT -> R.string.voice_widget_design_amount
                            VoiceWidgetDesign.RING -> R.string.voice_widget_design_ring
                            VoiceWidgetDesign.GRAPH_BG -> R.string.voice_widget_design_graph_bg
                        }
                    ),
                    description = stringResource(
                        when (option) {
                            VoiceWidgetDesign.PERCENT -> R.string.voice_widget_design_percent_desc
                            VoiceWidgetDesign.AMOUNT -> R.string.voice_widget_design_amount_desc
                            VoiceWidgetDesign.RING -> R.string.voice_widget_design_ring_desc
                            VoiceWidgetDesign.GRAPH_BG -> R.string.voice_widget_design_graph_bg_desc
                        }
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
            ButtonRow(
                modifier = Modifier.fillMaxWidth(),
                icon = painterResource(R.drawable.ic_apply),
                text = stringResource(R.string.voice_widget_configure_add),
                onClick = { onApply(selected) },
            )
        }
    }
}
