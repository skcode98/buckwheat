package com.danilkinkin.buckwheat.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

const val RECURRING_PAYMENTS_SHEET = "recurringPayments"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPaymentsSheet(
    viewModel: RecurringPaymentsViewModel = hiltViewModel(),
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    suggestedAmount: BigDecimal? = null,
    suggestedComment: String? = null,
    suggestedDay: Int? = null,
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val currency by spendsViewModel.currency.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val autoApplyMode by viewModel.autoApplyMode.collectAsStateWithLifecycle()

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var showCreateForm by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf(suggestedAmount?.toPlainString() ?: "") }
    var commentText by remember { mutableStateOf(suggestedComment ?: "") }
    var dayText by remember { mutableStateOf(suggestedDay?.toString() ?: "") }

    fun submitTemplate() {
        val amount = amountText.toBigDecimalOrNull()
        val day = dayText.toIntOrNull()
        if (amount != null && day != null && commentText.isNotBlank()) {
            viewModel.addTemplate(amount, commentText, day)
            amountText = ""
            commentText = ""
            dayText = ""
            showCreateForm = false
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.recurring_payments_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Auto-apply section
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.recurring_auto_apply_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.recurring_auto_apply_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RecurringAutoApplyMode.values().forEach { mode ->
                                    FilterChip(
                                        selected = autoApplyMode == mode,
                                        onClick = { viewModel.setAutoApplyMode(mode) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (mode) {
                                                        RecurringAutoApplyMode.OFF ->
                                                            R.string.recurring_auto_apply_off
                                                        RecurringAutoApplyMode.ASK ->
                                                            R.string.recurring_auto_apply_ask
                                                        RecurringAutoApplyMode.SILENT ->
                                                            R.string.recurring_auto_apply_silent
                                                    }
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                // Recurring payment cards
                if (templates.isEmpty() && !showCreateForm) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_autorenew),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.recurring_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }

                items(templates, key = { it.id }) { template ->
                    RecurringPaymentCard(
                        template = template,
                        currency = currency,
                        onToggle = { viewModel.toggleEnabled(template) },
                        onDelete = { viewModel.deleteTemplate(template.id) },
                        modifier = Modifier.animateItem(),
                    )
                }

                // Create new payment
                item(key = "add_button") {
                    AnimatedVisibility(
                        visible = !showCreateForm,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        OutlinedButton(
                            onClick = { showCreateForm = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(vertical = 16.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.recurring_add_new))
                        }
                    }
                }

                item(key = "create_form") {
                    AnimatedVisibility(
                        visible = showCreateForm,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        CreatePaymentCard(
                            amountText = amountText,
                            commentText = commentText,
                            dayText = dayText,
                            onAmountChange = { amountText = it },
                            onCommentChange = { commentText = it },
                            onDayChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                            onSubmit = { submitTemplate() },
                            onCancel = {
                                showCreateForm = false
                                amountText = ""
                                commentText = ""
                                dayText = ""
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecurringPaymentCard(
    template: RecurringTemplate,
    currency: ExtendCurrency,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val alpha = if (template.enabled) 1f else 0.5f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Day badge
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = template.dayOfMonth.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.comment,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = numberFormat(context, template.amount, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = nextChargeText(context, template.dayOfMonth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Toggle
            Switch(
                checked = template.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                ),
            )

            // Delete
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_forever),
                    contentDescription = stringResource(R.string.tags_management_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CreatePaymentCard(
    amountText: String,
    commentText: String,
    dayText: String,
    onAmountChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onDayChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.recurring_amount_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = dayText,
                    onValueChange = onDayChange,
                    modifier = Modifier.width(72.dp),
                    placeholder = { Text(stringResource(R.string.recurring_day_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = commentText,
                onValueChange = onCommentChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.recurring_comment_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onSubmit() }) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

private fun nextChargeText(context: android.content.Context, dayOfMonth: Int): String {
    val now = java.util.Calendar.getInstance()
    val today = now.get(java.util.Calendar.DAY_OF_MONTH)
    val target = dayOfMonth.coerceAtMost(now.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))

    return when {
        today == target -> context.getString(R.string.recurring_next_charge_today)
        today < target -> context.getString(R.string.recurring_next_charge_days, target - today)
        else -> {
            val nextMonth = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.MONTH, 1)
            }
            val maxNext = nextMonth.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val nextTarget = dayOfMonth.coerceAtMost(maxNext)
            context.getString(R.string.recurring_next_charge_days, nextTarget + (now.getActualMaximum(java.util.Calendar.DAY_OF_MONTH) - today))
        }
    }
}

@Preview
@Composable
private fun PreviewRecurringPayments() {
    BuckwheatTheme {
        RecurringPaymentsSheet()
    }
}
