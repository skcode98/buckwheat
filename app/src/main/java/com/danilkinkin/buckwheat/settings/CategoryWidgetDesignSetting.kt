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
import com.danilkinkin.buckwheat.di.categoryWidgetDesignStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.widget.category.CategoryWidgetDesign
import com.danilkinkin.buckwheat.widget.category.CategoryWidgetReceiver
import com.danilkinkin.buckwheat.widget.WidgetReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

const val SETTINGS_CHANGE_CATEGORY_WIDGET_DESIGN_SHEET = "settings.changeCategoryWidgetDesign"

// Persists the chosen category widget design and asks the placed category widgets to re-render.
suspend fun switchCategoryWidgetDesign(context: Context, design: CategoryWidgetDesign) {
    context.settingsDataStore.edit {
        it[categoryWidgetDesignStoreKey] = design.name
    }
    WidgetReceiver.requestUpdateData(context, CategoryWidgetReceiver::class.java)
}

@HiltViewModel
class CategoryWidgetDesignViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    var design = settingsRepository.getCategoryWidgetDesign().asLiveData()

    fun setDesign(context: Context, design: CategoryWidgetDesign) {
        viewModelScope.launch {
            switchCategoryWidgetDesign(context, design)
        }
    }
}

@Composable
fun CategoryWidgetDesignSetting(
    appViewModel: AppViewModel = hiltViewModel(),
    viewModel: CategoryWidgetDesignViewModel = hiltViewModel(),
) {
    val design by viewModel.design.observeAsState(CategoryWidgetDesign.BATTERY)

    ButtonRow(
        icon = painterResource(R.drawable.ic_widgets),
        text = stringResource(R.string.category_widget_design_title),
        endCaption = stringResource(
            when (design) {
                CategoryWidgetDesign.BATTERY -> R.string.category_widget_design_battery
                CategoryWidgetDesign.COMPACT -> R.string.category_widget_design_compact
            }
        ),
        onClick = {
            appViewModel.openSheet(PathState(SETTINGS_CHANGE_CATEGORY_WIDGET_DESIGN_SHEET))
        },
    )
}

@Composable
fun CategoryWidgetDesignSettingDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: CategoryWidgetDesignViewModel = hiltViewModel()
    val design by viewModel.design.observeAsState(CategoryWidgetDesign.BATTERY)

    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    fun handleSwitch(selected: CategoryWidgetDesign) {
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
                    text = stringResource(R.string.category_widget_design_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            CheckedRow(
                checked = design == CategoryWidgetDesign.BATTERY,
                onValueChange = { handleSwitch(CategoryWidgetDesign.BATTERY) },
                text = stringResource(R.string.category_widget_design_battery),
                description = stringResource(R.string.category_widget_design_battery_desc),
            )
            CheckedRow(
                checked = design == CategoryWidgetDesign.COMPACT,
                onValueChange = { handleSwitch(CategoryWidgetDesign.COMPACT) },
                text = stringResource(R.string.category_widget_design_compact),
                description = stringResource(R.string.category_widget_design_compact_desc),
            )
        }
    }
}
