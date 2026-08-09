/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.backup

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.data.LedgerViewModel
import kotlinx.coroutines.launch

@Composable
fun rememberImportCsv(
    ledgerViewModel: LedgerViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    onImported: (Int) -> Unit,
    onFailed: () -> Unit = {},
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var openFileLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>? = null

    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
    ) {
        openFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri === null) return@rememberLauncherForActivityResult

            coroutineScope.launch {
                try {
                    val csv = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return@launch
                    onImported(ledgerViewModel.importCsv(csv))
                } catch (e: Exception) {
                    onFailed()
                }
            }
        }
    }

    return {
        openFileLauncher?.launch(arrayOf("text/*"))
    }
}
