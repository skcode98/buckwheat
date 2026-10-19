package com.danilkinkin.buckwheat.editor.dateTimeEdit

import java.time.LocalDate

internal data class DatePickerWindow(
    val disableBeforeDate: LocalDate?,
    val disableAfterDate: LocalDate,
    val showBeforeDate: LocalDate,
    val showAfterDate: LocalDate,
)

internal fun pickerWindow(
    periodStart: LocalDate?,
    periodFinish: LocalDate?,
    today: LocalDate,
): DatePickerWindow {
    val before = periodStart?.takeIf { !it.isAfter(today) } ?: today
    val after = periodFinish?.takeIf { !it.isBefore(today) } ?: today
    return DatePickerWindow(
        disableBeforeDate = before,
        disableAfterDate = today,
        showBeforeDate = before,
        showAfterDate = after,
    )
}
