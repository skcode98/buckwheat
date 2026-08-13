package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.contentColorFor
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
import com.danilkinkin.buckwheat.data.categories.SpendCategoriesViewModel
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_MODEL
import com.danilkinkin.buckwheat.di.DEFAULT_VOICE_AI_PROVIDER_URL
import com.danilkinkin.buckwheat.di.normalizeVoiceAiModel
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.AiConnectionResult
import com.danilkinkin.buckwheat.keyboard.isValidAiProviderUrl
import com.danilkinkin.buckwheat.keyboard.testAiConnection
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.util.combineColors
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
        mutableStateOf(DEFAULT_VOICE_AI_PROVIDER_URL)
    }
    var voiceAiModel by rememberSaveable { mutableStateOf(DEFAULT_VOICE_AI_MODEL) }

    var testState by remember { mutableStateOf<AiConnectionResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun runTest() {
        val scope = coroutineScope
        testing = true
        testState = null
        scope.launch {
            testState = testAiConnection(
                context = context,
                providerUrl = voiceAiProviderUrl,
                apiKey = voiceAiApiKey,
                model = voiceAiModel,
            )
            testing = false
        }
    }

    LaunchedEffect(Unit) {
        voiceAiApiKey = context.settingsDataStore.data.first()[voiceAiApiKeyStoreKey].orEmpty()
        voiceAiProviderUrl = context.settingsDataStore.data.first()[voiceAiProviderUrlStoreKey]
            .orEmpty()
            .ifBlank { DEFAULT_VOICE_AI_PROVIDER_URL }
        voiceAiModel = normalizeVoiceAiModel(
            context.settingsDataStore.data.first()[voiceAiModelStoreKey]
        )
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VOICE_AI_MODEL
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
                AiIntelligenceSetting()
                CategoryAutoAssignSetting()
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
                            val url = voiceAiProviderUrl.trim()
                            if (!isValidAiProviderUrl(url)) {
                                appViewModel.showSnackbar(
                                    context.getString(R.string.voice_ai_invalid_url)
                                )
                                return@launch
                            }
                            context.settingsDataStore.edit {
                                if (voiceAiApiKey.isBlank()) {
                                    it.remove(voiceAiApiKeyStoreKey)
                                } else {
                                    it[voiceAiApiKeyStoreKey] = voiceAiApiKey.trim()
                                }
                                if (url.isBlank()) {
                                    it.remove(voiceAiProviderUrlStoreKey)
                                } else {
                                    it[voiceAiProviderUrlStoreKey] = url
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { runTest() },
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (testing) R.string.voice_ai_testing
                                else R.string.voice_ai_test_connection
                            )
                        )
                    }
                }
                val testResult = testState
                when {
                    testResult is AiConnectionResult.Success -> {
                        Text(
                            text = stringResource(R.string.voice_ai_test_success, testResult.reply),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorGood,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    testResult is AiConnectionResult.Failure -> {
                        Text(
                            text = stringResource(R.string.voice_ai_test_failed, testResult.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
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

// Manual trigger for the shared background categorization pass: offline keywords first, then AI
// for anything still uncategorized. Already-categorized spends are skipped (see CategoryAssigner).
// The pass runs on an application-scoped coroutine so it keeps going after this sheet closes.
@Composable
private fun CategoryAutoAssignSetting(
    spendCategoriesViewModel: SpendCategoriesViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isCategorizing by spendCategoriesViewModel.isCategorizing.observeAsState(false)
    val uncategorizedCount by spendCategoriesViewModel.uncategorizedCount.observeAsState(0)

    val iconTint = contentColorFor(
        combineColors(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.surfaceVariant,
            angle = 0.3F,
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_label),
                    tint = iconTint,
                    contentDescription = null,
                )
            }
            Text(
                text = stringResource(R.string.category_auto_run_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    spendCategoriesViewModel.categorizeUncategorized()
                    coroutineScope.launch {
                        appViewModel.showSnackbar(context.getString(R.string.category_auto_started))
                    }
                },
                enabled = !isCategorizing,
            ) {
                Text(
                    text = if (isCategorizing) {
                        stringResource(R.string.category_auto_running)
                    } else {
                        stringResource(R.string.category_auto_run)
                    }
                )
            }
        }
        Text(
            text = stringResource(R.string.category_auto_run_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 80.dp, end = 8.dp, bottom = 8.dp),
        )
        when {
            isCategorizing -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 80.dp, end = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            uncategorizedCount > 0 -> {
                Text(
                    text = stringResource(R.string.category_auto_pending, uncategorizedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 80.dp, end = 8.dp, bottom = 8.dp),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.category_auto_all_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorGood,
                    modifier = Modifier.padding(start = 80.dp, end = 8.dp, bottom = 8.dp),
                )
            }
        }
    }
}
