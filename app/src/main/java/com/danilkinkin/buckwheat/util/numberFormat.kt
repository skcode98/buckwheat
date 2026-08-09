package com.danilkinkin.buckwheat.util

import android.content.Context
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.*

// Get localised float divider (',' or '.')
fun getFloatDivider(): String {
    val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.getDefault())

    numberFormat.maximumFractionDigits = 1
    numberFormat.minimumFractionDigits = 1

    val formattedValue = numberFormat.format(1.0)

    return formattedValue.substring(1, 2)
}

fun numberFormat(
    context: Context,
    value: BigDecimal,
    currency: ExtendCurrency,
    trimDecimalPlaces: Boolean = false,
    forceShowAfterDot: Boolean = false,
    applyRoundValues: Boolean = true,
    maximumFractionDigits: Int = if (forceShowAfterDot) 5 else 2,
    minimumFractionDigits: Int = if (forceShowAfterDot) 1 else 0,
): String {
    var valueFinal = value
    var decimalPlace = ""
    val formatter = if (currency.type === ExtendCurrency.Type.FROM_LIST) {
        NumberFormat.getCurrencyInstance(Locale.getDefault())
    } else {
        NumberFormat.getNumberInstance(Locale.getDefault())
    }

    // "Round values" drops the decimals everywhere except live-typed amount
    // inputs (callers opt out via applyRoundValues / forceShowAfterDot).
    val roundToWhole = applyRoundValues && NumberDisplayConfig.roundValues && !forceShowAfterDot
    formatter.maximumFractionDigits = if (roundToWhole) 0 else maximumFractionDigits
    formatter.minimumFractionDigits = if (roundToWhole) 0 else minimumFractionDigits

    if (currency.type === ExtendCurrency.Type.FROM_LIST) formatter.currency =
        Currency.getInstance(currency.value)

    val thousand = BigDecimal(1000)
    var overflow = false
    if (trimDecimalPlaces) {
        valueFinal = if (value >= thousand.pow(4) * BigDecimal(100)) {
            decimalPlace = context.getString(R.string.trillion_numerical_shorthand)
            overflow = true
            100.toBigDecimal()
        } else if (value >= thousand.pow(4)) {
            decimalPlace = context.getString(R.string.trillion_numerical_shorthand)
            value / (thousand.pow(4))
        } else if (value >= thousand.pow(3)) {
            decimalPlace = context.getString(R.string.billion_numerical_shorthand)
            value / (thousand.pow(3))
        } else if (value >= thousand.pow(2)) {
            decimalPlace = context.getString(R.string.million_numerical_shorthand)
            value / (thousand.pow(2))
        } else if (value >= thousand * BigDecimal(100)) {
            decimalPlace = context.getString(R.string.thousand_numerical_shorthand)
            value / thousand
        } else {
            value
        }
    }


    var formattedValue = formatter.format(valueFinal)

    if (currency.type === ExtendCurrency.Type.CUSTOM) formattedValue = "$formattedValue ${currency.value}"

    return "${if(overflow) "> " else ""}$formattedValue $decimalPlace"
}