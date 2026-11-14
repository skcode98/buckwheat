package com.danilkinkin.buckwheat.settings

import java.math.BigDecimal

// Leftover daily budget = the part of today's allowance that has not been spent yet.
// Never negative: an overspent day leaves nothing to auto-allocate.
internal fun dailyLeftover(
    dailyBudget: BigDecimal,
    spentFromDailyBudget: BigDecimal,
): BigDecimal = (dailyBudget - spentFromDailyBudget).coerceAtLeast(BigDecimal.ZERO)
