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
import androidx.compose.runtime.key
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
import com.danilkinkin.buckwheat.ai.AiProvider
import com.danilkinkin.buckwheat.ai.AiProviderConfig
import com.danilkinkin.buckwheat.ai.AiRouterResult
import com.danilkinkin.buckwheat.ai.aiApiKeyStoreKey
import com.danilkinkin.buckwheat.ai.aiModelStoreKey
import com.danilkinkin.buckwheat.ai.aiProviderOrderStoreKey
import com.danilkinkin.buckwheat.ai.aiProviderUrlStoreKey
import com.danilkinkin.buckwheat.ai.resolveProviderOrder
import com.danilkinkin.buckwheat.ai.testProviderConnection
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.categories.SpendCategoriesViewModel
import com.danilkinkin.buckwheat.di.normalizeVoiceAiModel
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

    var apiKeys by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var providerUrls by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var models by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var testStates by remember { mutableStateOf<Map<String, AiRouterResult>>(emptyMap()) }
    var testingProvider by remember { mutableStateOf<String?>(null) }
    var providerOrder by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        providerOrder = resolveProviderOrder(prefs).map { it.id }
        apiKeys = AiProvider.FALLBACK_ORDER.associate { provider ->
            var key = prefs[aiApiKeyStoreKey(provider)].orEmpty()
            if (key.isBlank() && provider == AiProvider.OPENROUTER) {
                key = prefs[voiceAiApiKeyStoreKey].orEmpty()
            }
            provider.id to key
        }
        providerUrls = AiProvider.FALLBACK_ORDER.associate { provider ->
            var url = prefs[aiProviderUrlStoreKey(provider)].orEmpty()
            if (url.isBlank() && provider == AiProvider.OPENROUTER) {
                url = prefs[voiceAiProviderUrlStoreKey].orEmpty()
            }
            provider.id to url.trim().ifBlank { provider.defaultUrl }
        }
        models = AiProvider.FALLBACK_ORDER.associate { provider ->
            var model = prefs[aiModelStoreKey(provider)].orEmpty()
            if (model.isBlank() && provider == AiProvider.OPENROUTER) {
                model = normalizeVoiceAiModel(prefs[voiceAiModelStoreKey]) ?: ""
            }
            provider.id to model.trim().ifBlank { provider.defaultModel }
        }
    }

    fun runTest(provider: AiProvider) {
        if (testingProvider != null) return
        testingProvider = provider.id
        testStates = testStates - provider.id
        coroutineScope.launch {
            testStates = testStates + (
                provider.id to testProviderConnection(
                    AiProviderConfig(
                        provider = provider,
                        apiKey = apiKeys[provider.id].orEmpty(),
                        url = providerUrls[provider.id].orEmpty(),
                        model = models[provider.id].orEmpty(),
                    )
                )
            )
            testingProvider = null
        }
    }

    fun moveProvider(id: String, delta: Int) {
        val index = providerOrder.indexOf(id)
        if (index < 0) return
        val newIndex = index + delta
        if (newIndex !in providerOrder.indices) return
        providerOrder = providerOrder.toMutableList().apply {
            removeAt(index)
            add(newIndex, id)
        }
    }

    fun saveAll() {
        coroutineScope.launch {
            for (provider in AiProvider.FALLBACK_ORDER) {
                val key = apiKeys[provider.id].orEmpty()
                if (key.isNotBlank() && !provider.isValidKey(key)) {
                    appViewModel.showSnackbar(
                        context.getString(R.string.ai_provider_invalid_key_hint)
                    )
                    return@launch
                }
            }
            for (provider in AiProvider.FALLBACK_ORDER) {
                val url = providerUrls[provider.id].orEmpty()
                if (url.isNotBlank() && !isValidAiProviderUrl(url)) {
                    appViewModel.showSnackbar(
                        context.getString(R.string.ai_provider_invalid_url_hint)
                    )
                    return@launch
                }
            }
            context.settingsDataStore.edit { prefs ->
                for (provider in AiProvider.FALLBACK_ORDER) {
                    val key = apiKeys[provider.id].orEmpty()
                    if (key.isBlank()) {
                        prefs.remove(aiApiKeyStoreKey(provider))
                    } else {
                        prefs[aiApiKeyStoreKey(provider)] = key.trim()
                    }
                    val url = providerUrls[provider.id].orEmpty().trim()
                    if (url.isBlank() || url == provider.defaultUrl) {
                        prefs.remove(aiProviderUrlStoreKey(provider))
                    } else {
                        prefs[aiProviderUrlStoreKey(provider)] = url
                    }
                    val model = models[provider.id].orEmpty().trim()
                    if (model.isBlank() || model == provider.defaultModel) {
                        prefs.remove(aiModelStoreKey(provider))
                    } else {
                        prefs[aiModelStoreKey(provider)] = model
                    }
                }
                if (providerOrder == AiProvider.FALLBACK_ORDER.map { it.id }) {
                    prefs.remove(aiProviderOrderStoreKey())
                } else {
                    prefs[aiProviderOrderStoreKey()] = providerOrder.joinToString(",")
                }
            }
            appViewModel.showSnackbar(context.getString(R.string.voice_ai_saved))
        }
    }

    val freeModels by voiceAiSettingsViewModel.freeModels.observeAsState(emptyMap())

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
                    text = stringResource(R.string.ai_providers_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                for (provider in providerOrder) {
                    val p = AiProvider.values().firstOrNull { it.id == provider } ?: continue
                    key(provider) {
                        val index = providerOrder.indexOf(provider)
                        AiProviderCard(
                            provider = p,
                            apiKey = apiKeys[provider].orEmpty(),
                            providerUrl = providerUrls[provider].orEmpty(),
                            model = models[provider].orEmpty(),
                            onApiKeyChange = { apiKeys = apiKeys + (provider to it) },
                            onProviderUrlChange = { providerUrls = providerUrls + (provider to it) },
                            onModelChange = { models = models + (provider to it) },
                            isTesting = testingProvider == provider,
                            testResult = testStates[provider],
                            onTest = { runTest(p) },
                            freeModels = freeModels[provider].orEmpty(),
                            onRefreshFreeModels = {
                                voiceAiSettingsViewModel.refreshFreeModels(
                                    p,
                                    apiKeys[p.id].orEmpty(),
                                    providerUrls[p.id].orEmpty(),
                                )
                            },
                            onMoveUp = if (index > 0) {
                                { moveProvider(provider, -1) }
                            } else {
                                null
                            },
                            onMoveDown = if (index < providerOrder.lastIndex) {
                                { moveProvider(provider, 1) }
                            } else {
                                null
                            },
                        )
                    }
                }
                Button(
                    onClick = { saveAll() },
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
private fun AiProviderCard(
    provider: AiProvider,
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
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    val keyStatus = when {
        apiKey.isBlank() -> Triple(R.string.ai_provider_key_not_set, MaterialTheme.colorScheme.onSurface.copy(0.6f), false)
        provider.isValidKey(apiKey) -> Triple(R.string.ai_provider_key_valid, colorGood, false)
        else -> Triple(R.string.ai_provider_key_invalid, MaterialTheme.colorScheme.error, true)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(keyStatus.first),
                        style = MaterialTheme.typography.bodySmall,
                        color = keyStatus.second,
                    )
                }
                AiTestDot(
                    isTesting = isTesting,
                    testResult = testResult,
                    onTest = onTest,
                )
                if (onMoveUp != null) {
                    IconButton(onClick = onMoveUp) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_up),
                            contentDescription = stringResource(R.string.ai_provider_move_up),
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(onClick = onMoveDown) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_down),
                            contentDescription = stringResource(R.string.ai_provider_move_down),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.voice_ai_api_key)) },
                isError = keyStatus.third,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = providerUrl,
                onValueChange = onProviderUrlChange,
                label = { Text(stringResource(R.string.voice_ai_provider_url)) },
                placeholder = { Text(provider.defaultUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = {
                    modelDropdownExpanded = !modelDropdownExpanded
                    // Real-time refresh of the provider's free-model list whenever the user opens
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
                    placeholder = { Text(provider.defaultModel) },
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
// a small spinner, and after a run a solid 8dp dot — green when the provider answered, red when it
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
