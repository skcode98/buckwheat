package com.danilkinkin.buckwheat.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.di.roundValuesStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RoundValuesSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var roundValues by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        roundValues = context.settingsDataStore.data.first()[roundValuesStoreKey] ?: false
    }

    fun setRoundValues(value: Boolean) {
        roundValues = value
        coroutineScope.launch {
            context.settingsDataStore.edit { it[roundValuesStoreKey] = value }
        }
    }

    ButtonRow(
        icon = painterResource(R.drawable.ic_currency),
        text = stringResource(R.string.round_values_title),
        description = stringResource(R.string.round_values_description),
        onClick = { setRoundValues(!roundValues) },
        endContent = {
            Switch(
                checked = roundValues,
                onCheckedChange = { setRoundValues(it) },
            )
        },
    )
}

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        RoundValuesSetting()
    }
}
