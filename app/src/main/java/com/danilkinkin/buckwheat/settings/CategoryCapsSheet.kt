package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.categoriesChart.baseColors
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.HarmonizedColorPalette
import com.danilkinkin.buckwheat.util.NumberDisplayConfig
import com.danilkinkin.buckwheat.util.harmonizeWithColor
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.util.toPalette
import java.math.BigDecimal
import java.math.RoundingMode

const val CATEGORY_CAPS_SHEET = "categoryCaps"

private val categoryColors: List<Color> = baseColors.map { color ->
    harmonizeWithColor(color, Color(0xFFCC4C08))
}

private fun categoryColorByName(name: String, builtInCategory: SpendCategory?): Color {
    return if (builtInCategory != null && builtInCategory != SpendCategory.OTHER) {
        categoryColors[builtInCategory.ordinal % categoryColors.size]
    } else {
        categoryColors[Math.floorMod(name.hashCode(), categoryColors.size)]
    }
}

private val statusGood = Color(0xFF40AC02)
private val statusWarn = Color(0xFFFABC20)
private val statusBad = Color(0xFFC70909)

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
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories, key = { "${it.id}_${it.name}" }) { item ->
                    val builtIn = SpendCategory.fromStored(item.name)
                    val catColor = categoryColorByName(item.name, builtIn)
                    CategoryCapRow(
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
private fun CategoryCapRow(
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
    var capText by remember(name, cap) { mutableStateOf(capDisplay ?: "") }

    val hasCap = cap != null && cap > BigDecimal.ZERO
    val percent = if (hasCap) categoryCapPercent(spent, cap!!) else 0
    val fraction = if (hasCap) (spent.toFloat() / cap!!.toFloat()).coerceIn(0f, 1f) else 0f
    val fractionDisplay = (fraction * 100).toInt()

    val barColor = when {
        !hasCap -> Color.Transparent
        percent >= 100 -> statusBad
        percent >= CATEGORY_CAP_NEAR_PERCENT -> statusWarn
        else -> categoryColor
    }
    val textColor = categoryColor

    val cardShape = RoundedCornerShape(22.dp)

    Surface(
        shape = cardShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            categoryColor.copy(alpha = 0.18f),
                            categoryColor.copy(alpha = 0.03f),
                        )
                    )
                )
                .padding(14.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = categoryDisplayName(name),
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hasCap) {
                            Text(
                                text = "${numberFormat(context, spent, currency)} spent",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!hasCap) {
                        OutlinedTextField(
                            value = capText,
                            onValueChange = { capText = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.width(120.dp),
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
                                        ?.let(onSave)
                                }
                            ),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${stringResource(R.string.category_caps_hint)} ${numberFormat(context, cap!!, currency)}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                text = categoryDisplayName(name).lowercase(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (hasCap) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(categoryColor.copy(alpha = 0.12f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(barColor),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "$fractionDisplay% used",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = barColor,
                        )
                        val remaining = cap!! - spent
                        val remainingDisplay = if (remaining > BigDecimal.ZERO) remaining else BigDecimal.ZERO
                        Text(
                            text = "${numberFormat(context, remainingDisplay, currency)} left",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                        ?.let(onSave)
                                }
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            capText = ""
                            onClear()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_forever),
                                contentDescription = stringResource(R.string.category_caps_remove),
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                        ?.let(onSave)
                                }
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewCategoryCaps() {
    BuckwheatTheme {
        CategoryCapsSheet()
    }
}
