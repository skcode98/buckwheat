package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.ui.BuckwheatTheme

const val RECURRING_PAYMENTS_SHEET = "recurringPayments"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPaymentsSheet(
    viewModel: RecurringPaymentsViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val templates by viewModel.templates.observeAsState(emptyList())

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var amountText by remember { mutableStateOf("") }
    var commentText by remember { mutableStateOf("") }
    var dayText by remember { mutableStateOf("") }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.recurring_payments_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.recurring_amount_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() }.take(2) },
                        modifier = Modifier.width(80.dp),
                        placeholder = { Text(stringResource(R.string.recurring_day_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val amount = amountText.toBigDecimalOrNull()
                                val day = dayText.toIntOrNull()
                                if (amount != null && day != null && commentText.isNotBlank()) {
                                    viewModel.addTemplate(amount, commentText, day)
                                    amountText = ""
                                    commentText = ""
                                    dayText = ""
                                }
                            }
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val amount = amountText.toBigDecimalOrNull()
                            val day = dayText.toIntOrNull()
                            if (amount != null && day != null && commentText.isNotBlank()) {
                                viewModel.addTemplate(amount, commentText, day)
                                amountText = ""
                                commentText = ""
                                dayText = ""
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.recurring_comment_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val amount = amountText.toBigDecimalOrNull()
                            val day = dayText.toIntOrNull()
                            if (amount != null && day != null && commentText.isNotBlank()) {
                                viewModel.addTemplate(amount, commentText, day)
                                amountText = ""
                                commentText = ""
                                dayText = ""
                            }
                        }
                    ),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                items(templates) { template ->
                    RecurringTemplateRow(
                        template = template,
                        onToggle = { viewModel.toggleEnabled(template) },
                        onDelete = { viewModel.deleteTemplate(template.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecurringTemplateRow(
    template: RecurringTemplate,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(44.dp)
                .weight(1f),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$" + template.amount.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = template.comment,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.recurring_day_label, template.dayOfMonth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        Switch(
            checked = template.enabled,
            onCheckedChange = { onToggle() },
        )
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_forever),
                contentDescription = stringResource(R.string.tags_management_delete),
            )
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
