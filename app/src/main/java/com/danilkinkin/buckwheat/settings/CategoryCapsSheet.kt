package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.analytics.categoriesChart.CategoryBatteryChip
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.NumberDisplayConfig
import java.math.BigDecimal
import java.math.RoundingMode

const val CATEGORY_CAPS_SHEET = "categoryCaps"

// Settings sheet for the monthly cap per category. Caps apply to the current budget period's
// spend totals and drive the 80%/100% instant notifications and the battery pill in the
// analytics categories card. "Auto-assign budget" splits the current budget across all
// categories by their typical monthly spend; each cap stays editable afterwards.
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
            ) {
                items(categories, key = { it }) { item ->
                    CategoryCapRow(
                        name = item.name,
                        emoji = SpendCategory.emojiFor(item.name, item.emoji),
                        cap = caps[item.name],
                        spent = categorySpends[item.name] ?: BigDecimal.ZERO,
                        currency = currency,
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
    onSave: (BigDecimal) -> Unit,
    onClear: () -> Unit,
) {
    val capDisplay = cap
        ?.takeIf { NumberDisplayConfig.roundValues }
        ?.setScale(0, RoundingMode.HALF_EVEN)
        ?.toPlainString()
        ?: cap?.toPlainString()
    var capText by remember(name, cap) { mutableStateOf(capDisplay ?: "") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$emoji  ${categoryDisplayName(name)}",
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = capText,
                onValueChange = { capText = it.filter { c -> c.isDigit() || c == '.' } },
                modifier = Modifier.width(132.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.category_caps_hint)) },
                placeholder = { Text(stringResource(R.string.category_caps_hint)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        capText.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
                            ?.let(onSave)
                    }
                ),
            )
            if (cap != null) {
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
        }
        if (cap != null && cap > BigDecimal.ZERO) {
            CategoryBatteryChip(
                name = categoryDisplayName(name),
                emoji = emoji,
                amount = spent,
                currency = currency,
                cap = cap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
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
