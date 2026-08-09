/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.home

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.danilkinkin.trackinvest.R
import com.danilkinkin.trackinvest.home.dashboard.Dashboard
import com.danilkinkin.trackinvest.home.portfolio.Portfolio
import com.danilkinkin.trackinvest.ledger.Ledger

enum class MainTab(val titleRes: Int) {
    DASHBOARD(R.string.tab_dashboard),
    LEDGER(R.string.tab_ledger),
    PORTFOLIO(R.string.tab_portfolio),
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
                                    when (tab) {
                                        MainTab.DASHBOARD -> R.drawable.ic_tab_dashboard
                                        MainTab.LEDGER -> R.drawable.ic_tab_ledger
                                        MainTab.PORTFOLIO -> R.drawable.ic_tab_portfolio
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
                MainTab.DASHBOARD -> Dashboard()
                MainTab.LEDGER -> Ledger(activityResultRegistryOwner)
                MainTab.PORTFOLIO -> Portfolio()
            }
        }
    }
}
