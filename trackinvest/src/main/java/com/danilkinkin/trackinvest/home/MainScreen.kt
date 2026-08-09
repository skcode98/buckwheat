/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.home

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.backup.rememberExportCsv
import com.danilkinkin.trackinvest.backup.rememberImportCsv
import com.danilkinkin.trackinvest.data.LedgerViewModel

enum class MainTab(val titleRes: Int) {
    DASHBOARD(R.string.tab_dashboard),
    LEDGER(R.string.tab_ledger),
}

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.DASHBOARD) }
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    if (tab == MainTab.DASHBOARD) {
                                        R.drawable.ic_tab_dashboard
                                    } else {
                                        R.drawable.ic_tab_ledger
                                    },
                                ),
                                contentDescription = stringResource(tab.titleRes),
                            )
                        },
                        label = { Text(stringResource(tab.titleRes)) },
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (selectedTab) {
                MainTab.DASHBOARD -> DashboardPlaceholder()
                MainTab.LEDGER -> LedgerTab(activityResultRegistryOwner)
            }
        }
    }
}

@Composable
private fun DashboardPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.tab_dashboard),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun LedgerTab(activityResultRegistryOwner: ActivityResultRegistryOwner?) {
    val ledgerViewModel: LedgerViewModel = hiltViewModel()
    val context = LocalContext.current
    var feedback by remember { mutableStateOf<String?>(null) }

    val exportCsv = rememberExportCsv(
        ledgerViewModel = ledgerViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
        onExported = { feedback = context.getString(R.string.csv_exported) },
        onFailed = { feedback = context.getString(R.string.csv_export_failed) },
    )

    val importCsv = rememberImportCsv(
        ledgerViewModel = ledgerViewModel,
        activityResultRegistryOwner = activityResultRegistryOwner,
        onImported = { count ->
            feedback = if (count > 0) {
                context.getString(R.string.csv_imported, count)
            } else {
                context.getString(R.string.csv_import_empty)
            }
        },
        onFailed = { feedback = context.getString(R.string.csv_import_failed) },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.tab_ledger),
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = exportCsv) {
            Text(stringResource(R.string.export_csv))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = importCsv) {
            Text(stringResource(R.string.import_csv))
        }

        feedback?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
