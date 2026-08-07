package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.base.CheckedRow
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.voiceWidgetDesignStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetDesign
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

const val SETTINGS_CHANGE_WIDGET_DESIGN_SHEET = "settings.changeWidgetDesign"

// Persists the chosen voice widget design and asks the placed voice widgets to re-render.
suspend fun switchVoiceWidgetDesign(context: Context, design: VoiceWidgetDesign) {
    context.settingsDataStore.edit {
        it[voiceWidgetDesignStoreKey] = design.name
    }
    WidgetReceiver.requestUpdateData(context, VoiceWidgetReceiver::class.java)
}

@HiltViewModel
class VoiceWidgetDesignViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    var design = settingsRepository.getVoiceWidgetDesign().asLiveData()

    fun setDesign(context: Context, design: VoiceWidgetDesign) {
        viewModelScope.launch {
            switchVoiceWidgetDesign(context, design)
        }
    }
}

@Composable
fun VoiceWidgetDesignSetting(
    appViewModel: AppViewModel = hiltViewModel(),
    viewModel: VoiceWidgetDesignViewModel = hiltViewModel(),
) {
    val design by viewModel.design.observeAsState(VoiceWidgetDesign.PERCENT)

    ButtonRow(
        icon = painterResource(R.drawable.ic_widgets),
        text = stringResource(R.string.voice_widget_design_title),
        endCaption = stringResource(
            when (design) {
                VoiceWidgetDesign.PERCENT -> R.string.voice_widget_design_percent
                VoiceWidgetDesign.AMOUNT -> R.string.voice_widget_design_amount
                VoiceWidgetDesign.RING -> R.string.voice_widget_design_ring
            }
        ),
        onClick = {
            appViewModel.openSheet(PathState(SETTINGS_CHANGE_WIDGET_DESIGN_SHEET))
        },
    )
}

@Composable
fun VoiceWidgetDesignSettingDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: VoiceWidgetDesignViewModel = hiltViewModel()
    val design by viewModel.design.observeAsState(VoiceWidgetDesign.PERCENT)

    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    fun handleSwitch(selected: VoiceWidgetDesign) {
        viewModel.setDesign(context, selected)
        onClose()
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column(modifier = Modifier.padding(bottom = navigationBarHeight)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.voice_widget_design_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            CheckedRow(
                checked = design == VoiceWidgetDesign.PERCENT,
                onValueChange = { handleSwitch(VoiceWidgetDesign.PERCENT) },
                text = stringResource(R.string.voice_widget_design_percent),
                description = stringResource(R.string.voice_widget_design_percent_desc),
            )
            CheckedRow(
                checked = design == VoiceWidgetDesign.AMOUNT,
                onValueChange = { handleSwitch(VoiceWidgetDesign.AMOUNT) },
                text = stringResource(R.string.voice_widget_design_amount),
                description = stringResource(R.string.voice_widget_design_amount_desc),
            )
            CheckedRow(
                checked = design == VoiceWidgetDesign.RING,
                onValueChange = { handleSwitch(VoiceWidgetDesign.RING) },
                text = stringResource(R.string.voice_widget_design_ring),
                description = stringResource(R.string.voice_widget_design_ring_desc),
            )
        }
    }
}
