package com.danilkinkin.buckwheat.settings

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.text.KeyboardOptions
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.data.AppLockViewModel
import com.danilkinkin.buckwheat.util.APP_LOCK_PIN_MAX_LENGTH
import com.danilkinkin.buckwheat.util.APP_LOCK_PIN_MIN_LENGTH
import com.danilkinkin.buckwheat.util.AppLockBiometricKey
import com.danilkinkin.buckwheat.util.combineColors
import com.danilkinkin.buckwheat.util.generatePinHash
import com.danilkinkin.buckwheat.util.isLegacyPinHash
import com.danilkinkin.buckwheat.util.verifyPinHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme as M3Theme

private enum class AppLockDialog { SETUP, MENU, CHANGE, REMOVE }

private data class SmartTimeoutOption(val labelRes: Int, val seconds: Int)

private val smartTimeoutOptions = listOf(
    SmartTimeoutOption(R.string.app_lock_smart_timeout_off, 0),
    SmartTimeoutOption(R.string.app_lock_smart_timeout_10s, 10),
    SmartTimeoutOption(R.string.app_lock_smart_timeout_30s, 30),
    SmartTimeoutOption(R.string.app_lock_smart_timeout_1m, 60),
    SmartTimeoutOption(R.string.app_lock_smart_timeout_5m, 300),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSetting(
    viewModel: AppLockViewModel,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = viewModel.repository
    var hasPin by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<AppLockDialog?>(null) }

    var smartTimeoutEnabled by remember { mutableStateOf(true) }
    var smartTimeoutSeconds by remember { mutableIntStateOf(30) }

    LaunchedEffect(Unit) {
        hasPin = repository.getPinHash() != null
        biometricEnabled = repository.isBiometricEnabled().first()
        smartTimeoutEnabled = repository.getSmartTimeoutEnabled().first()
        smartTimeoutSeconds = repository.getSmartTimeoutSeconds().first()
    }

    val canUseBiometric = remember {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    val iconTint = contentColorFor(
        combineColors(
            M3Theme.colorScheme.secondaryContainer,
            M3Theme.colorScheme.surfaceVariant,
            angle = 0.3F,
        )
    )

    TextRow(
        icon = painterResource(R.drawable.ic_lock),
        text = stringResource(R.string.app_lock_title),
        description = stringResource(R.string.app_lock_description),
        endIcon = painterResource(R.drawable.ic_arrow_right),
        modifier = Modifier.clickable {
            activeDialog = if (hasPin) AppLockDialog.MENU else AppLockDialog.SETUP
        },
    )

    if (hasPin) {
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
                    painter = painterResource(R.drawable.ic_fingerprint),
                    tint = iconTint,
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_lock_biometric_title),
                    style = M3Theme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.app_lock_biometric_description),
                    style = M3Theme.typography.bodySmall,
                    color = M3Theme.colorScheme.onSurfaceVariant,
                )
            }
                    Switch(
                    checked = biometricEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val activity = context as? FragmentActivity
                            if (activity == null) {
                                biometricEnabled = false
                                return@Switch
                            }
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    AppLockBiometricKey.createKey()
                                }
                                val cipher = withContext(Dispatchers.IO) {
                                    AppLockBiometricKey.createEncryptCipher()
                                }
                                if (cipher == null) {
                                    biometricEnabled = false
                                    return@launch
                                }
                                val cryptoObject = BiometricPrompt.CryptoObject(cipher)
                                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                    .setTitle(context.getString(R.string.app_lock_biometric_prompt_title))
                                    .setSubtitle(context.getString(R.string.app_lock_biometric_prompt_subtitle))
                                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                    .setNegativeButtonText(context.getString(R.string.app_lock_biometric_prompt_cancel))
                                    .build()
                                val biometricPrompt = BiometricPrompt(
                                    activity,
                                    ContextCompat.getMainExecutor(activity),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(
                                            result: BiometricPrompt.AuthenticationResult,
                                        ) {
                                            coroutineScope.launch {
                                                val pair = withContext(Dispatchers.IO) {
                                                    AppLockBiometricKey.encryptWithCipher(cipher)
                                                }
                                                if (pair != null) {
                                                    repository.setBiometricSecret(pair.first, pair.second)
                                                    repository.setBiometricEnabled(true)
                                                    biometricEnabled = true
                                                } else {
                                                    biometricEnabled = false
                                                }
                                            }
                                        }

                                        override fun onAuthenticationError(
                                            errorCode: Int,
                                            errString: CharSequence,
                                        ) {
                                            biometricEnabled = false
                                        }
                                    },
                                )
                                biometricPrompt.authenticate(promptInfo, cryptoObject)
                            }
                        } else {
                            AppLockBiometricKey.deleteKey()
                            coroutineScope.launch {
                                repository.setBiometricEnabled(false)
                            }
                            biometricEnabled = false
                        }
                    },
                    enabled = canUseBiometric,
                )
        }

        // Smart timeout config
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_lock_smart_timeout),
                    style = M3Theme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.app_lock_smart_timeout_desc),
                    style = M3Theme.typography.bodySmall,
                    color = M3Theme.colorScheme.onSurfaceVariant,
                )
            }
            var expanded by remember { mutableStateOf(false) }
            val selectedLabel = smartTimeoutOptions
                .firstOrNull { it.seconds == smartTimeoutSeconds }
                ?.let { stringResource(it.labelRes) }
                ?: stringResource(R.string.app_lock_smart_timeout_off)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .width(120.dp),
                    textStyle = M3Theme.typography.bodyMedium,
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    smartTimeoutOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            onClick = {
                                smartTimeoutSeconds = option.seconds
                                expanded = false
                                coroutineScope.launch {
                                    repository.setSmartTimeoutSeconds(option.seconds)
                                    if (option.seconds > 0) {
                                        repository.setSmartTimeoutEnabled(true)
                                        smartTimeoutEnabled = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    when (activeDialog) {
        AppLockDialog.SETUP -> PinSetupDialog(
            repository = repository,
            onDone = {
                hasPin = true
                biometricEnabled = false
                activeDialog = null
                coroutineScope.launch { viewModel.refresh() }
            },
            onCancel = { activeDialog = null },
        )

        AppLockDialog.MENU -> AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text(stringResource(R.string.app_lock_title)) },
            text = {
                Column {
                    TextButton(
                        onClick = { activeDialog = AppLockDialog.CHANGE },
                    ) {
                        Text(stringResource(R.string.app_lock_change_pin))
                    }
                    TextButton(
                        onClick = { activeDialog = AppLockDialog.REMOVE },
                    ) {
                        Text(stringResource(R.string.app_lock_remove_pin))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )

        AppLockDialog.CHANGE -> PinChangeDialog(
            repository = repository,
            onDone = {
                activeDialog = null
                coroutineScope.launch { viewModel.refresh() }
            },
            onCancel = { activeDialog = null },
        )

        AppLockDialog.REMOVE -> AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text(stringResource(R.string.app_lock_remove_title)) },
            text = { Text(stringResource(R.string.app_lock_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    AppLockBiometricKey.deleteKey()
                    coroutineScope.launch {
                        repository.setPinHash(null)
                        repository.setEnabled(false)
                    }
                    hasPin = false
                    biometricEnabled = false
                    activeDialog = null
                    coroutineScope.launch { viewModel.refresh() }
                }) {
                    Text(stringResource(R.string.app_lock_remove_pin))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )

        null -> Unit
    }
}

@Composable
private fun PinSetupDialog(
    repository: com.danilkinkin.buckwheat.data.AppLockRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun onPinChange(value: String) {
        pin = value.filter { it.isDigit() }.take(APP_LOCK_PIN_MAX_LENGTH)
        error = null
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (step == 0) R.string.app_lock_setup_title
                    else R.string.app_lock_confirm_title,
                )
            )
        },
        text = {
            Column {
                PinInputField(pin = pin, onPinChange = ::onPinChange)
                error?.let {
                    Text(
                        text = it,
                        style = M3Theme.typography.bodySmall,
                        color = M3Theme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> {
                        if (pin.length < APP_LOCK_PIN_MIN_LENGTH ||
                            pin.length > APP_LOCK_PIN_MAX_LENGTH
                        ) {
                            error = context.getString(
                                R.string.app_lock_pin_invalid,
                                APP_LOCK_PIN_MIN_LENGTH,
                                APP_LOCK_PIN_MAX_LENGTH,
                            )
                        } else {
                            firstPin = pin
                            pin = ""
                            step = 1
                            error = null
                        }
                    }

                    else -> {
                        if (pin != firstPin) {
                            error = context.getString(R.string.app_lock_pin_mismatch)
                        } else {
                val hash = generatePinHash(pin)
                coroutineScope.launch {
                    repository.setPinHash(hash)
                    repository.setEnabled(true)
                    repository.setBiometricEnabled(false)
                    onDone()
                }
                        }
                    }
                }
            }) {
                Text(stringResource(R.string.app_lock_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PinChangeDialog(
    repository: com.danilkinkin.buckwheat.data.AppLockRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var newPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var storedHash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        storedHash = repository.getPinHash()
    }

    fun onPinChange(value: String) {
        pin = value.filter { it.isDigit() }.take(APP_LOCK_PIN_MAX_LENGTH)
        error = null
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    when (step) {
                        0 -> R.string.app_lock_pin_current
                        1 -> R.string.app_lock_setup_title
                        else -> R.string.app_lock_confirm_title
                    }
                )
            )
        },
        text = {
            Column {
                PinInputField(pin = pin, onPinChange = ::onPinChange)
                error?.let {
                    Text(
                        text = it,
                        style = M3Theme.typography.bodySmall,
                        color = M3Theme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> {
                        if (storedHash != null && verifyPinHash(pin, storedHash)) {
                            if (isLegacyPinHash(storedHash)) {
                                val upgraded = generatePinHash(pin)
                                storedHash = upgraded
                                coroutineScope.launch {
                                    repository.setPinHash(upgraded)
                                }
                            }
                            pin = ""
                            step = 1
                            error = null
                        } else {
                            error = context.getString(R.string.app_lock_pin_wrong)
                        }
                    }

                    1 -> {
                        if (pin.length < APP_LOCK_PIN_MIN_LENGTH ||
                            pin.length > APP_LOCK_PIN_MAX_LENGTH
                        ) {
                            error = context.getString(
                                R.string.app_lock_pin_invalid,
                                APP_LOCK_PIN_MIN_LENGTH,
                                APP_LOCK_PIN_MAX_LENGTH,
                            )
                        } else {
                            newPin = pin
                            pin = ""
                            step = 2
                            error = null
                        }
                    }

                    else -> {
                        if (pin != newPin) {
                            error = context.getString(R.string.app_lock_pin_mismatch)
                        } else {
                            val hash = generatePinHash(pin)
                            coroutineScope.launch {
                                repository.setPinHash(hash)
                                onDone()
                            }
                        }
                    }
                }
            }) {
                Text(stringResource(R.string.app_lock_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PinInputField(
    pin: String,
    onPinChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
