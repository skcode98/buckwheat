package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

const val VOICE_AI_SETTINGS_SHEET = "voiceAiSettings"

@Composable
fun VoiceAiSettingsSheet() {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var voiceAiApiKey by remember { mutableStateOf("") }
    var voiceAiProviderUrl by remember { mutableStateOf("https://openrouter.ai/api/v1/chat/completions") }
    var voiceAiModel by remember { mutableStateOf("google/gemma-3n-e4b-it:free") }

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    LaunchedEffect(Unit) {
        voiceAiApiKey = context.settingsDataStore.data.first()[voiceAiApiKeyStoreKey].orEmpty()
        voiceAiProviderUrl = context.settingsDataStore.data.first()[voiceAiProviderUrlStoreKey]
            .orEmpty()
            .ifBlank { "https://openrouter.ai/api/v1/chat/completions" }
        voiceAiModel = context.settingsDataStore.data.first()[voiceAiModelStoreKey]
            .orEmpty()
            .ifBlank { "google/gemma-3n-e4b-it:free" }
    }

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
                OutlinedTextField(
                    value = voiceAiModel,
                    onValueChange = { voiceAiModel = it },
                    label = { Text(stringResource(R.string.voice_ai_model)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
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
