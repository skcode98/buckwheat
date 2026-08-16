package com.danilkinkin.buckwheat.ui

import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.AppLockViewModel
import com.danilkinkin.buckwheat.util.AppLockBiometricKey
import androidx.biometric.BiometricPrompt.CryptoObject

// Full-screen gate shown while the app is locked. Renders instead of the main screen so no
// app content is reachable until the PIN (or biometrics) unlocks it.
@Composable
fun AppLockScreen(
    viewModel: AppLockViewModel,
) {
    val context = LocalContext.current
    val pinFocusRequester = remember { FocusRequester() }

    // Only strong biometrics (hardware fingerprint/face) back the Keystore key used for the
    // unlock; weak modalities like camera face-unlock are never accepted for the app lock.
    val allowedAuthenticators = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }
    val canUseBiometric = remember(allowedAuthenticators) {
        BiometricManager.from(context).canAuthenticate(allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    val biometricPrompt = remember {
        val activity = context as? FragmentActivity
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        val cipher = result.cryptoObject?.cipher
                        val secret = viewModel.biometricSecret
                        if (cipher != null &&
                            secret != null &&
                            AppLockBiometricKey.verifySecret(cipher, secret)
                        ) {
                            viewModel.unlockWithBiometric()
                        } else {
                            viewModel.disableBiometric()
                        }
                    }
                },
            )
        }
    }

    fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.app_lock_biometric_prompt_title))
            .setSubtitle(context.getString(R.string.app_lock_biometric_prompt_subtitle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(allowedAuthenticators)
        } else {
            builder.setAllowedAuthenticators(allowedAuthenticators)
                .setNegativeButtonText(context.getString(R.string.app_lock_biometric_prompt_cancel))
        }
        return builder.build()
    }

    fun promptBiometric() {
        val iv = viewModel.biometricIv ?: run {
            viewModel.disableBiometric()
            return
        }
        val cipher = AppLockBiometricKey.createDecryptCipher(iv)
        if (cipher == null) {
            viewModel.disableBiometric()
            return
        }
        biometricPrompt?.authenticate(buildPromptInfo(), CryptoObject(cipher))
    }

    // Never let the recents/overview task snapshot leak spend content while the lock is up.
    val activity = context as? FragmentActivity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(Unit) {
        pinFocusRequester.requestFocus()
        if (viewModel.biometricEnabled && canUseBiometric && viewModel.biometricIv != null) {
            promptBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_lock_locked_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_lock_locked_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = viewModel.pinInput,
                onValueChange = viewModel::onPinChange,
                singleLine = true,
                label = { Text(stringResource(R.string.app_lock_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.verifyPin() }),
                isError = viewModel.unlockError,
                enabled = viewModel.lockoutSecondsLeft <= 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(pinFocusRequester),
            )
            if (viewModel.lockoutSecondsLeft > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.app_lock_too_many_attempts,
                        viewModel.lockoutSecondsLeft,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (viewModel.unlockError && viewModel.lockoutSecondsLeft <= 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.app_lock_pin_wrong),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (viewModel.biometricEnabled && canUseBiometric) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = { promptBiometric() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fingerprint),
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.app_lock_biometric_title))
                }
            }
        }
    }
}
