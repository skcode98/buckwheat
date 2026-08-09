package com.danilkinkin.buckwheat.util

// Cached copy of the "round values" setting so the synchronous, context-free
// `numberFormat()` helper can decide whether to drop the fractional digits.
// Kept in sync with the settings DataStore by the Application.
object NumberDisplayConfig {
    @Volatile
    var roundValues: Boolean = false
}
