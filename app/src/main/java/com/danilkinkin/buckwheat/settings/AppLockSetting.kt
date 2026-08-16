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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.TextRow
import com.danilkinkin.buckwheat.di.appLockBiometricEnabledStoreKey
import com.danilkinkin.buckwheat.di.appLockBiometricIvStoreKey
import com.danilkinkin.buckwheat.di.appLockBiometricSecretStoreKey
import com.danilkinkin.buckwheat.di.appLockEnabledStoreKey
import com.danilkinkin.buckwheat.di.appLockPinHashStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
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
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.biometric.BiometricPrompt.CryptoObject
import javax.crypto.Cipher

private enum class AppLockDialog { SETUP, MENU, CHANGE, REMOVE }

@Composable
fun AppLockSetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var hasPin by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<AppLockDialog?>(null) }

    LaunchedEffect(Unit) {
        val prefs = context.settingsDataStore.data.first()
        hasPin = prefs[appLockPinHashStoreKey] != null
        biometricEnabled = prefs[appLockBiometricEnabledStoreKey] ?: false
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
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.surfaceVariant,
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
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.app_lock_biometric_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = biometricEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        coroutineScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    AppLockBiometricKey.createKey()
                                }
                                val cipher = Cipher.getInstance(AppLockBiometricKey.TRANSFORMATION)
                                val activity = context as? FragmentActivity
                                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                    .setTitle(context.getString(R.string.app_lock_biometric_prompt_title))
                                    .setSubtitle(context.getString(R.string.app_lock_biometric_prompt_subtitle))
                                    .setAllowedAuthenticators(
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            BiometricManager.Authenticators.BIOMETRIC_STRONG
                                        } else {
                                            BiometricManager.Authenticators.BIOMETRIC_WEAK
                                        }
                                    )
                                    .build()
                                val biometricPrompt = BiometricPrompt(
                                    activity!!,
                                    ContextCompat.getMainExecutor(activity),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            val authCipher = result.cryptoObject?.cipher
                                            if (authCipher != null) {
                                                val pair = AppLockBiometricKey.encryptSecret(authCipher)
                                                if (pair != null) {
                                                    coroutineScope.launch {
                                                        context.settingsDataStore.edit {
                                                            it[appLockBiometricEnabledStoreKey] = true
                                                            it[appLockBiometricIvStoreKey] = pair.first
                                                            it[appLockBiometricSecretStoreKey] = pair.second
                                                        }
                                                        biometricEnabled = true
                                                    }
                                                } else {
                                                    biometricEnabled = false
                                                }
                                            } else {
                                                biometricEnabled = false
                                            }
                                        }

                                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                            biometricEnabled = false
                                        }
                                    },
                                )
                                biometricPrompt.authenticate(promptInfo, CryptoObject(cipher))
                            } catch (e: Exception) {
                                biometricEnabled = false
                            }
                        }
                    } else {
                        AppLockBiometricKey.deleteKey()
                        coroutineScope.launch {
                            context.settingsDataStore.edit {
                                it[appLockBiometricEnabledStoreKey] = false
                                it.remove(appLockBiometricIvStoreKey)
                                it.remove(appLockBiometricSecretStoreKey)
                            }
                        }
                        biometricEnabled = false
                    }
                },
                enabled = canUseBiometric,
            )
        }
    }

    when (activeDialog) {
        AppLockDialog.SETUP -> PinSetupDialog(
            onDone = {
                hasPin = true
                biometricEnabled = false
                activeDialog = null
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
            onDone = { activeDialog = null },
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
                        context.settingsDataStore.edit {
                            it.remove(appLockPinHashStoreKey)
                            it.remove(appLockBiometricEnabledStoreKey)
                            it.remove(appLockBiometricIvStoreKey)
                            it.remove(appLockBiometricSecretStoreKey)
                            it[appLockEnabledStoreKey] = false
                        }
                    }
                    hasPin = false
                    biometricEnabled = false
                    activeDialog = null
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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
                            coroutineScope.launch {
                                context.settingsDataStore.edit {
                                    it[appLockPinHashStoreKey] = generatePinHash(pin)
                                    it[appLockEnabledStoreKey] = true
                                    it[appLockBiometricEnabledStoreKey] = false
                                }
                            }
                            onDone()
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
        storedHash = context.settingsDataStore.data.first()[appLockPinHashStoreKey]
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    0 -> {
                        if (storedHash != null && verifyPinHash(pin, storedHash)) {
                            // Migrate hashes written by the legacy SHA-256 scheme to PBKDF2.
                            if (isLegacyPinHash(storedHash)) {
                                val upgraded = generatePinHash(pin)
                                storedHash = upgraded
                                coroutineScope.launch {
                                    context.settingsDataStore.edit {
                                        it[appLockPinHashStoreKey] = upgraded
                                    }
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
                            coroutineScope.launch {
                                context.settingsDataStore.edit {
                                    it[appLockPinHashStoreKey] = generatePinHash(pin)
                                }
                            }
                            onDone()
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
