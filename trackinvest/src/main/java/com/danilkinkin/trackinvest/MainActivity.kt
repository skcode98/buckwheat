/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.danilkinkin.trackinvest.home.MainScreen
import com.danilkinkin.trackinvest.ui.TrackInvestTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackInvestTheme {
                MainScreen()
            }
        }
    }
}
