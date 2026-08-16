package com.danilkinkin.buckwheat

import OverrideLocalize
import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.danilkinkin.buckwheat.base.balloon.BalloonProvider
import com.danilkinkin.buckwheat.data.AppLockViewModel
import com.danilkinkin.buckwheat.home.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.AppLockScreen
import com.danilkinkin.buckwheat.ui.ThemeMode
import com.danilkinkin.buckwheat.ui.syncTheme
import com.danilkinkin.buckwheat.util.locScreenOrientation
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import syncOverrideLocale
import java.util.*
import javax.inject.Inject

val Context.budgetDataStore by preferencesDataStore("budget")
val Context.settingsDataStore by preferencesDataStore("settings")
var Context.appTheme by mutableStateOf(ThemeMode.SYSTEM)
var Context.appLocale: Locale? by mutableStateOf(null)
var Context.systemLocale: Locale? by mutableStateOf(null)
var Context.errorForReport: String? by mutableStateOf(null)

val LocalWindowSize = compositionLocalOf { WindowWidthSizeClass.Compact }
val LocalWindowInsets = compositionLocalOf { PaddingValues(0.dp) }

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    // Broke tests. Set to true for tests
    private val isDone: MutableState<Boolean> = mutableStateOf(false)
    private val isReady: MutableState<Boolean> = mutableStateOf(false)

    private val appLockViewModel: AppLockViewModel by viewModels()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onStop() {
        super.onStop()
        // Re-arm the app lock whenever the activity actually leaves the foreground. Skipped on
        // configuration changes (rotation), which also go through onStop without a real exit.
        if (!isChangingConfigurations) {
            appLockViewModel.armLock()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val context = this.applicationContext
        WindowCompat.setDecorFitsSystemWindows(window, false)
        installSplashScreen().setKeepOnScreenCondition { !isDone.value }
        lifecycleScope.launch {
            context.settingsDataStore.data.first()
        }

        super.onCreate(savedInstanceState)

        // Opened from the voice widget's mic button (or its "permission needed" notification):
        // prompt for the microphone so the widget can work without ever opening the app again.
        // On API 33+, POST_NOTIFICATIONS is requested alongside it, otherwise the widget's
        // result and "permission needed" notifications are silently dropped.
        if (intent?.getBooleanExtra(VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION, false) == true) {
            intent.removeExtra(VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION)
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            micPermissionLauncher.launch(permissions.toTypedArray())
        }

        setContent {
            val localContext = LocalContext.current
            val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current

            LaunchedEffect(Unit) {
                syncTheme(localContext)
                syncOverrideLocale(localContext)

                // App ready for work
                isReady.value = true
            }

            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass

            if (widthSizeClass == WindowWidthSizeClass.Compact) {
                locScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            }

            val windowInsets = WindowInsets
                .systemBars
                .asPaddingValues()

            if (isReady.value) {
                BuckwheatTheme {
                    OverrideLocalize {
                        BalloonProvider {
                            CompositionLocalProvider(
                                LocalWindowSize provides widthSizeClass,
                                LocalWindowInsets provides windowInsets,
                            ) {
                                if (appLockViewModel.isLocked) {
                                    AppLockScreen(appLockViewModel)
                                } else {
                                    MainScreen(activityResultRegistryOwner)
                                }

                                LaunchedEffect(Unit) {
                                    // App rendered and splash screen can be hidden
                                    isDone.value = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
