package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val VOICE_AI_SETTINGS_SHEET = "voiceAiSettings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAiSettingsSheet(
    appViewModel: AppViewModel = hiltViewModel(),
    voiceAiSettingsViewModel: VoiceAiSettingsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var voiceAiApiKey by rememberSaveable { mutableStateOf("") }
    var voiceAiProviderUrl by rememberSaveable {
        mutableStateOf("https://openrouter.ai/api/v1/chat/completions")
    }
    var voiceAiModel by rememberSaveable { mutableStateOf("nvidia/nemotron-3-ultra-550b-a55b:free") }

    LaunchedEffect(Unit) {
        voiceAiApiKey = context.settingsDataStore.data.first()[voiceAiApiKeyStoreKey].orEmpty()
        voiceAiProviderUrl = context.settingsDataStore.data.first()[voiceAiProviderUrlStoreKey]
            .orEmpty()
            .ifBlank { "https://openrouter.ai/api/v1/chat/completions" }
        voiceAiModel = context.settingsDataStore.data.first()[voiceAiModelStoreKey]
            .orEmpty()
            .ifBlank { "nvidia/nemotron-3-ultra-550b-a55b:free" }
    }

    val freeModels by voiceAiSettingsViewModel.freeModels.observeAsState(emptyList())
    var modelDropdownExpanded by remember { mutableStateOf(false) }

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
                    text = stringResource(R.string.voice_ai_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                Text(
                    text = stringResource(R.string.voice_ai_optional),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                OutlinedTextField(
                    value = voiceAiApiKey,
                    onValueChange = { voiceAiApiKey = it },
                    label = { Text(stringResource(R.string.voice_ai_api_key)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                OutlinedTextField(
                    value = voiceAiProviderUrl,
                    onValueChange = { voiceAiProviderUrl = it },
                    label = { Text(stringResource(R.string.voice_ai_provider_url)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = {
                        modelDropdownExpanded = !modelDropdownExpanded
                        // Real-time refresh of the free-models list straight from OpenRouter
                        // whenever the user opens the dropdown, so the picks are never stale.
                        if (modelDropdownExpanded) voiceAiSettingsViewModel.refreshFreeModels()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = voiceAiModel,
                        onValueChange = { voiceAiModel = it },
                        label = { Text(stringResource(R.string.voice_ai_model)) },
                        placeholder = { Text(stringResource(R.string.voice_ai_model_hint)) },
                        trailingIcon = {
                            IconButton(onClick = {
                                modelDropdownExpanded = !modelDropdownExpanded
                                if (modelDropdownExpanded) voiceAiSettingsViewModel.refreshFreeModels()
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_down),
                                    contentDescription = stringResource(R.string.voice_ai_fetch_models),
                                )
                            }
                        },
                        // Anchoring the dropdown to the text field (MenuAnchor).
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false },
                    ) {
                        freeModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.name)
                                        Text(
                                            text = model.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                                        )
                                    }
                                },
                                onClick = {
                                    voiceAiModel = model.id
                                    modelDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        coroutineScope.launch {
                            context.settingsDataStore.edit {
                                if (voiceAiApiKey.isBlank()) {
                                    it.remove(voiceAiApiKeyStoreKey)
                                } else {
                                    it[voiceAiApiKeyStoreKey] = voiceAiApiKey.trim()
                                }
                                if (voiceAiProviderUrl.isBlank()) {
                                    it.remove(voiceAiProviderUrlStoreKey)
                                } else {
                                    it[voiceAiProviderUrlStoreKey] = voiceAiProviderUrl.trim()
                                }
                                if (voiceAiModel.isBlank()) {
                                    it.remove(voiceAiModelStoreKey)
                                } else {
                                    it[voiceAiModelStoreKey] = voiceAiModel.trim()
                                }
                            }
                            appViewModel.showSnackbar(
                                context.getString(R.string.voice_ai_saved)
                            )
                        }
                    },
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.save_api_key))
                }
                Text(
                    text = stringResource(R.string.voice_ai_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Preview(name = "Default")
@Composable
private fun PreviewDefault() {
    BuckwheatTheme {
        VoiceAiSettingsSheet()
    }
}
