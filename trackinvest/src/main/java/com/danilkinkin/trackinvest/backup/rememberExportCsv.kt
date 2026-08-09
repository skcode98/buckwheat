/*
 * Copyright 2026, skcode98, All rights reserved.
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
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.data.LedgerViewModel
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun rememberExportCsv(
    ledgerViewModel: LedgerViewModel = hiltViewModel(),
    activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    onExported: () -> Unit = {},
    onFailed: () -> Unit = {},
): () -> Unit {
    if (activityResultRegistryOwner === null) return {}

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val fileName = context.getString(R.string.export_csv_file_name, LocalDate.now().toString())

    var createFileLauncher: ManagedActivityResultLauncher<String, Uri?>? = null

    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
    ) {
        createFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            if (uri === null) return@rememberLauncherForActivityResult

            coroutineScope.launch {
                try {
                    val csv = ledgerViewModel.exportCsv()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(csv.toByteArray(Charsets.UTF_8))
                    }
                    onExported()
                } catch (e: Exception) {
                    onFailed()
                }
            }
        }
    }

    return {
        try {
            createFileLauncher?.launch(fileName)
        } catch (e: Exception) {
            onFailed()
        }
    }
}
