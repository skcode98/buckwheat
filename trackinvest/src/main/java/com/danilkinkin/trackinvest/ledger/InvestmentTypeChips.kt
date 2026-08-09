/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.ledger

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val INVESTMENT_TYPES = listOf("FD", "PPF", "PF", "SIP", "Liquid", "Home", "Cash", "Stocks")

@Composable
internal fun InvestmentTypeChips(
    type: String,
    onTypeChange: (String) -> Unit,
) {
    FlowRow {
        INVESTMENT_TYPES.forEach { option ->
            FilterChip(
                selected = type == option,
                onClick = { onTypeChange(option) },
                label = { Text(option) },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}
