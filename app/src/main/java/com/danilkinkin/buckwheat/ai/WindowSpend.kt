package com.danilkinkin.buckwheat.ai

import java.math.BigDecimal
import java.util.Date

// Minimal view of a spend fed to the pure AI-insight math so it never touches Room entities.
// `category` is the stored category column (null = uncategorized).
data class WindowSpend(
    val date: Date,
    val value: BigDecimal,
    val category: String?,
)
