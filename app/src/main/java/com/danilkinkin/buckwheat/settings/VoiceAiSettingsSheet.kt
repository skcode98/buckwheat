package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.ai.AiBackendConfig
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.testAiConnection
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.categories.SpendCategoriesViewModel
import com.danilkinkin.buckwheat.di.voiceAiApiKeyStoreKey
import com.danilkinkin.buckwheat.di.voiceAiModelStoreKey
import com.danilkinkin.buckwheat.di.voiceAiProviderUrlStoreKey
import com.danilkinkin.buckwheat.keyboard.isValidAiProviderUrl
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

    var apiKey by rememberSaveable { mutableStateOf("") }
    var providerUrl by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var testResult by remember { mutableStateOf<AiRouterResult?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        apiKey = prefs[voiceAiApiKeyStoreKey].orEmpty()
        providerUrl = prefs[voiceAiProviderUrlStoreKey].orEmpty()
        model = prefs[voiceAiModelStoreKey].orEmpty()
    }

    fun runTest() {
        if (testing) return
        testing = true
        testResult = null
        coroutineScope.launch {
            testResult = testAiConnection(
                AiBackendConfig(
                    url = providerUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                )
            )
            testing = false
        }
    }

    fun save() {
        coroutineScope.launch {
            val url = providerUrl.trim()
            val key = apiKey.trim()
            val savedModel = model.trim()
            if (url.isNotBlank() && !isValidAiProviderUrl(url)) {
                appViewModel.showSnackbar(
                    context.getString(R.string.voice_ai_invalid_url)
                )
                return@launch
            }
            context.settingsDataStore.edit { prefs ->
                if (key.isBlank()) {
                    prefs.remove(voiceAiApiKeyStoreKey)
                } else {
                    prefs[voiceAiApiKeyStoreKey] = key
                }
                if (url.isBlank()) {
                    prefs.remove(voiceAiProviderUrlStoreKey)
                } else {
                    prefs[voiceAiProviderUrlStoreKey] = url
                }
                if (savedModel.isBlank()) {
                    prefs.remove(voiceAiModelStoreKey)
                } else {
                    prefs[voiceAiModelStoreKey] = savedModel
                }
            }
            appViewModel.showSnackbar(context.getString(R.string.voice_ai_saved))
        }
    }

    val freeModels by voiceAiSettingsViewModel.freeModels.observeAsState(emptyList())

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
                    text = stringResource(R.string.ai_engine_title),
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
                    text = stringResource(R.string.ai_backend_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                AiBackendCard(
                    apiKey = apiKey,
                    providerUrl = providerUrl,
                    model = model,
                    onApiKeyChange = { apiKey = it },
                    onProviderUrlChange = { providerUrl = it },
                    onModelChange = { model = it },
                    isTesting = testing,
                    testResult = testResult,
                    onTest = { runTest() },
                    freeModels = freeModels,
                    onRefreshFreeModels = {
                        voiceAiSettingsViewModel.refreshFreeModels(
                            apiKey.trim(),
                            providerUrl.trim(),
                        )
                    },
                )
                Button(
                    onClick = { save() },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiBackendCard(
    apiKey: String,
    providerUrl: String,
    model: String,
    onApiKeyChange: (String) -> Unit,
    onProviderUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    isTesting: Boolean,
    testResult: AiRouterResult?,
    onTest: () -> Unit,
    freeModels: List<FreeModel>,
    onRefreshFreeModels: () -> Unit,
) {
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_backend_connection),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.ai_backend_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                AiTestDot(
                    isTesting = isTesting,
                    testResult = testResult,
                    onTest = onTest,
                )
            }
            OutlinedTextField(
                value = providerUrl,
                onValueChange = onProviderUrlChange,
                label = { Text(stringResource(R.string.voice_ai_provider_url)) },
                placeholder = { Text("https://…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.voice_ai_api_key)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = {
                    modelDropdownExpanded = !modelDropdownExpanded
                    // Real-time refresh of the backend's model list whenever the user opens
                    // the dropdown, so the picks are never stale.
                    if (modelDropdownExpanded) onRefreshFreeModels()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text(stringResource(R.string.voice_ai_model)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            modelDropdownExpanded = !modelDropdownExpanded
                            if (modelDropdownExpanded) onRefreshFreeModels()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_down),
                                contentDescription = stringResource(R.string.voice_ai_fetch_models),
                            )
                        }
                    },
                    modifier = Modifier.menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                ) {
                    if (freeModels.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.ai_provider_no_models),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    freeModels.forEach { freeModel ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(freeModel.name)
                                    Text(
                                        text = freeModel.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                                    )
                                }
                            },
                            onClick = {
                                onModelChange(freeModel.id)
                                modelDropdownExpanded = false
                            },
                        )
                    }
                }
            }
            when (val result = testResult) {
                is AiRouterResult.Success -> Text(
                    text = stringResource(R.string.voice_ai_test_success, result.text),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorGood,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is AiRouterResult.Failure -> Text(
                    text = stringResource(R.string.voice_ai_test_failed, result.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                AiRouterResult.NotConfigured -> Unit
                null -> Unit
            }
        }
    }
}

// The connection test as a tiny check-circle button: untested shows a plain check, during the test
// a small spinner, and after a run a solid 8dp dot — green when the backend answered, red when it
// failed. The underlying test never throws, so the dot just stays neutral if nothing is configured.
@Composable
private fun AiTestDot(
    isTesting: Boolean,
    testResult: AiRouterResult?,
    onTest: () -> Unit,
) {
    val dotColor = when (testResult) {
        is AiRouterResult.Success -> colorGood
        is AiRouterResult.Failure -> MaterialTheme.colorScheme.error
        else -> null
    }
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = !isTesting, onClick = onTest),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isTesting -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            dotColor != null -> Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_apply),
                contentDescription = stringResource(R.string.voice_ai_test_connection),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
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
