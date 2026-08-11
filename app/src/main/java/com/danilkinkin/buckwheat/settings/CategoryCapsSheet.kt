package com.danilkinkin.buckwheat.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
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
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.prettyDate
import com.danilkinkin.buckwheat.util.toDate
import java.math.BigDecimal
import java.time.LocalDate

const val CATEGORY_CAPS_SHEET = "categoryCaps"

// A ready-made schedule set applied by one tap. Amounts are starter values the user edits;
// categories are the built-in stored names so the window math matches what gets persisted.
private data class CategoryCapTemplate(
    @StringRes val labelRes: Int,
    val frequency: CategoryFrequency,
    val categories: List<String>,
    val defaultAmount: BigDecimal,
)

private val categoryCapTemplates = listOf(
    CategoryCapTemplate(
        labelRes = R.string.category_caps_template_monthly_essentials,
        frequency = CategoryFrequency.MONTHLY,
        categories = listOf("FOOD", "TRANSPORT", "BILLS"),
        defaultAmount = BigDecimal("5000"),
    ),
    CategoryCapTemplate(
        labelRes = R.string.category_caps_template_quarterly_big_tickets,
        frequency = CategoryFrequency.QUARTERLY,
        categories = listOf("HEALTH", "SHOPPING", "ENTERTAINMENT"),
        defaultAmount = BigDecimal("15000"),
    ),
    CategoryCapTemplate(
        labelRes = R.string.category_caps_template_annual_obligations,
        frequency = CategoryFrequency.ANNUAL,
        categories = listOf("TRAVEL", "BILLS"),
        defaultAmount = BigDecimal("60000"),
    ),
)

// Settings sheet for the monthly-style cap per category. Caps apply to the current budget
// period's spend totals and drive the 80%/100% instant notifications and the progress bars
// in the analytics categories card. A category with a schedule entry (frequency + anchor)
// becomes an interleaved budget that rolls over on its own window instead of the period.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryCapsSheet(
    onEditAnchor: ((name: String, anchorEpochDay: Long) -> Unit)? = null,
    categoriesViewModel: CategoriesManagementViewModel = hiltViewModel(),
    capsViewModel: CategoryCapsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val categories by categoriesViewModel.allCategories.observeAsState(emptyList())
    val caps by capsViewModel.caps.observeAsState(emptyMap())
    val schedules by capsViewModel.interleaved.observeAsState(emptyMap())

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
            Text(
                text = stringResource(R.string.category_caps_templates_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                categoryCapTemplates.forEachIndexed { index, template ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {
                            capsViewModel.applyTemplate(
                                template.frequency,
                                template.categories,
                                template.defaultAmount,
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(template.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            ) {
                items(categories) { item ->
                    CategoryCapRow(
                        name = item.name,
                        emoji = SpendCategory.emojiFor(item.name, item.emoji),
                        cap = caps[item.name],
                        schedule = schedules[item.name],
                        onSave = { capsViewModel.setCap(item.name, it) },
                        onClear = { capsViewModel.setCap(item.name, null) },
                        onSetInterleaved = { frequency, anchorEpochDay ->
                            capsViewModel.setInterleaved(item.name, frequency, anchorEpochDay)
                        },
                        onEditAnchor = onEditAnchor,
                    )
                }
            }
        }
    }
}

@Composable
private fun frequencyLabel(frequency: CategoryFrequency): String = when (frequency) {
    CategoryFrequency.DAILY -> stringResource(R.string.category_caps_frequency_daily)
    CategoryFrequency.MONTHLY -> stringResource(R.string.category_caps_frequency_monthly)
    CategoryFrequency.QUARTERLY -> stringResource(R.string.category_caps_frequency_quarterly)
    CategoryFrequency.ANNUAL -> stringResource(R.string.category_caps_frequency_annual)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryCapRow(
    name: String,
    emoji: String,
    cap: BigDecimal?,
    schedule: InterleavedCategory?,
    onSave: (BigDecimal) -> Unit,
    onClear: () -> Unit,
    onSetInterleaved: (CategoryFrequency, Long) -> Unit,
    onEditAnchor: ((String, Long) -> Unit)?,
) {
    var capText by remember(name, cap) { mutableStateOf(cap?.toPlainString() ?: "") }
    val frequency = schedule?.frequency ?: CategoryFrequency.DAILY
    val anchorEpochDay = schedule?.anchorEpochDay ?: LocalDate.now().toEpochDay()
    var frequencyExpanded by remember(name) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExposedDropdownMenuBox(
                expanded = frequencyExpanded,
                onExpandedChange = { frequencyExpanded = !frequencyExpanded },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = frequencyLabel(frequency),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.category_caps_frequency)) },
                    trailingIcon = {
                        IconButton(onClick = { frequencyExpanded = !frequencyExpanded }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_down),
                                contentDescription = stringResource(R.string.category_caps_frequency),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = frequencyExpanded,
                    onDismissRequest = { frequencyExpanded = false },
                ) {
                    CategoryFrequency.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(frequencyLabel(candidate)) },
                            onClick = {
                                frequencyExpanded = false
                                onSetInterleaved(candidate, anchorEpochDay)
                            },
                        )
                    }
                }
            }
            if (frequency != CategoryFrequency.DAILY) {
                Spacer(Modifier.width(12.dp))
                val anchorText = prettyDate(
                    LocalDate.ofEpochDay(anchorEpochDay).toDate(),
                    "dd MMM yyyy",
                    simplifyIfToday = false,
                )
                Text(
                    text = anchorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = onEditAnchor != null) {
                            onEditAnchor?.invoke(name, anchorEpochDay)
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
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
