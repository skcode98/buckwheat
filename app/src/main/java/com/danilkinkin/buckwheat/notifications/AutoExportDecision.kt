package com.danilkinkin.buckwheat.notifications

// Pure gate for the end-of-period auto-export, fed a snapshot of the current DataStore state.
// JVM-testable so the receiver stays thin.
fun shouldAutoExportPeriod(
    toggleEnabled: Boolean,
    finishPeriodActualDateMillis: Long?,
    startPeriodMillis: Long?,
    finishPeriodMillis: Long?,
    lastAutoExportedPeriodStart: Long?,
    nowMillis: Long,
): Boolean {
    if (!toggleEnabled) return false
    if (finishPeriodActualDateMillis != null) return false
    val startMillis = startPeriodMillis ?: return false
    val finishMillis = finishPeriodMillis ?: return false
    if (finishMillis > nowMillis) return false
    if (lastAutoExportedPeriodStart == startMillis) return false
    return true
}