package com.danilkinkin.buckwheat.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.categoriesChart.baseColors
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.util.NumberDisplayConfig
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.math.RoundingMode

const val CATEGORY_CAPS_SHEET = "categoryCaps"

@Composable
private fun rememberCategoryColors(): List<Color> {
    val primary = MaterialTheme.colorScheme.primary
    return remember(primary) {
        baseColors.map { color -> harmonizeWithColor(color, primary) }
    }
}

private fun categoryColorByName(categoryColors: List<Color>, name: String, builtInCategory: SpendCategory?): Color {
    return if (builtInCategory != null && builtInCategory != SpendCategory.OTHER) {
        categoryColors[builtInCategory.ordinal % categoryColors.size]
    } else {
        categoryColors[Math.floorMod(name.hashCode(), categoryColors.size)]
    }
}

@Composable
fun CategoryCapsSheet(
    categoriesViewModel: CategoriesManagementViewModel = hiltViewModel(),
    capsViewModel: CategoryCapsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val categories by categoriesViewModel.allCategories.collectAsStateWithLifecycle()
    val caps by capsViewModel.caps.collectAsStateWithLifecycle()
    val categorySpends by capsViewModel.categorySpends.collectAsStateWithLifecycle()
    val currency by capsViewModel.currency.collectAsStateWithLifecycle()
    val categoryColors = rememberCategoryColors()

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.category_caps_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = stringResource(R.string.category_caps_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Button(
                onClick = { capsViewModel.autoAssignBudget(categories.map { it.name }) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_autorenew),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.category_caps_auto_assign))
            }
            Text(
                text = stringResource(R.string.category_caps_auto_assign_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(categories, key = { "${it.id}_${it.name}" }) { item ->
                    val builtIn = SpendCategory.fromStored(item.name)
                    val catColor = categoryColorByName(categoryColors, item.name, builtIn)
                    CategoryCapCard(
                        name = item.name,
                        emoji = SpendCategory.emojiFor(item.name, item.emoji),
                        cap = caps[item.name],
                        spent = categorySpends[item.name] ?: BigDecimal.ZERO,
                        currency = currency,
                        categoryColor = catColor,
                        onSave = { capsViewModel.setCap(item.name, it) },
                        onClear = { capsViewModel.setCap(item.name, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCapCard(
    name: String,
    emoji: String,
    cap: BigDecimal?,
    spent: BigDecimal,
    currency: ExtendCurrency,
    categoryColor: Color,
    onSave: (BigDecimal) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val capDisplay = cap
        ?.takeIf { NumberDisplayConfig.roundValues }
        ?.setScale(0, RoundingMode.HALF_EVEN)
        ?.toPlainString()
        ?: cap?.toPlainString()

    var isEditing by remember(name, cap) { mutableStateOf(false) }
    var capText by remember(name, cap) { mutableStateOf(capDisplay ?: "") }

    val hasCap = cap != null && cap > BigDecimal.ZERO
    val percent = if (hasCap) categoryCapPercent(spent, cap!!) else 0
    val fraction = if (hasCap) (spent.toFloat() / cap!!.toFloat()).coerceIn(0f, 1f) else 0f
    val fractionDisplay = (fraction * 100).toInt()

    val barColor = when {
        !hasCap -> categoryColor
        percent >= 100 -> MaterialTheme.colorScheme.error
        percent >= CATEGORY_CAP_NEAR_PERCENT -> MaterialTheme.colorScheme.tertiary
        else -> categoryColor
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: emoji + name + cap amount / action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = categoryDisplayName(name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasCap) {
                        Text(
                            text = "${numberFormat(context, spent, currency)} ${stringResource(R.string.category_caps_spent).lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hasCap && !isEditing) {
                    Text(
                        text = numberFormat(context, cap!!, currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = barColor,
                    )
                } else if (!hasCap && !isEditing) {
                    TextButton(onClick = {
                        isEditing = true
                        capText = ""
                    }) {
                        Text(stringResource(R.string.category_caps_set))
                    }
                }
            }

            // Progress bar
            if (hasCap && !isEditing) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(categoryColor.copy(alpha = 0.12f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$fractionDisplay% ${stringResource(R.string.category_caps_used)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = barColor,
                    )
                    val remaining = cap!! - spent
                    val remainingDisplay = if (remaining > BigDecimal.ZERO) remaining else BigDecimal.ZERO
                    Text(
                        text = "${numberFormat(context, remainingDisplay, currency)} ${stringResource(R.string.category_caps_left)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Edit + delete actions
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = {
                        isEditing = true
                        capText = capDisplay ?: ""
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.history_actions_edit),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = {
                        capText = ""
                        onClear()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_forever),
                            contentDescription = stringResource(R.string.category_caps_remove),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Edit mode: inline field
            AnimatedVisibility(
                visible = isEditing,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (hasCap) 0.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = capText,
                        onValueChange = { capText = it.filter { c -> c.isDigit() || c == '.' } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.category_caps_hint)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                capText.trim().toBigDecimalOrNull()
                                    ?.takeIf { it > BigDecimal.ZERO }
                                    ?.let {
                                        onSave(it)
                                        isEditing = false
                                    }
                            }
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        if (hasCap) {
                            isEditing = false
                            capText = capDisplay ?: ""
                        } else {
                            isEditing = false
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            // No cap: show set prompt inline
            if (!hasCap && !isEditing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.category_caps_no_cap),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.clickable {
                        isEditing = true
                        capText = ""
                    },
                )
            }
        }
    }
}
