package com.danilkinkin.buckwheat.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.overlay.BubbleLauncher
import com.danilkinkin.buckwheat.overlay.bubbleSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Master switch for the floating voice bubble. Turning ON requires the system
// "display over other apps" permission: the toggle immediately sends the user to the
// system screen and only latches enabled=true after returning with the grant. Turning
// OFF hides the bubble and permanently disables the capability until re-enabled.
@Composable
fun BubbleOverlaySetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var bubbleVisible by remember { mutableStateOf(false) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            coroutineScope.launch {
                bubbleSettings(context).setEnabled(true)
                enabled = true
                BubbleLauncher.show(context)
            }
        } else {
            enabled = false
        }
    }

    LaunchedEffect(Unit) {
        enabled = bubbleSettings(context).isEnabled().first()
        bubbleVisible = enabled && Settings.canDrawOverlays(context)
    }

    ButtonRow(
        icon = painterResource(R.drawable.ic_mic),
        text = stringResource(R.string.bubble_setting_title),
        description = stringResource(R.string.bubble_setting_description),
        onClick = {
            if (enabled) {
                coroutineScope.launch {
                    bubbleSettings(context).setEnabled(false)
                    enabled = false
                    BubbleLauncher.hide(context)
                    bubbleVisible = false
                }
            } else {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        },
        endContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    if (!want) {
                        coroutineScope.launch {
                            bubbleSettings(context).setEnabled(false)
                            enabled = false
                            BubbleLauncher.hide(context)
                            bubbleVisible = false
                        }
                    } else {
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
        },
    )

    if (enabled) {
        ButtonRow(
            icon = painterResource(R.drawable.ic_widgets),
            text = stringResource(
                if (bubbleVisible) R.string.bubble_hide else R.string.bubble_show,
            ),
            onClick = {
                if (bubbleVisible) {
                    BubbleLauncher.hide(context)
                    bubbleVisible = false
                } else {
                    if (Settings.canDrawOverlays(context)) {
                        BubbleLauncher.show(context)
                        bubbleVisible = true
                    } else {
                        enabled = false
                        coroutineScope.launch { bubbleSettings(context).setEnabled(false) }
                    }
                }
            },
        )
    }
}
