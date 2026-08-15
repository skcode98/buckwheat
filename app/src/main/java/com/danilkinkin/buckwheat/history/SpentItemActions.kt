package com.danilkinkin.buckwheat.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction

fun buildSpendCopyText(amount: String, comment: String): String =
    if (comment.isBlank()) amount else "$amount — $comment"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpentItemActions(
    transaction: Transaction,
    currency: ExtendCurrency,
    modifier: Modifier = Modifier,
    categoryEmojis: Map<String, String> = emptyMap(),
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val category = remember(transaction, categoryEmojis) {
        categoryLabelFor(context, transaction, categoryEmojis)
    }

    Box(modifier.combinedClickable(
        onClick = onEdit,
        onLongClick = { actionsMenuExpanded = true },
    )) {
        TimelineRowContent(
            transaction = transaction,
            currency = currency,
            category = category,
        )
        DropdownMenu(
            expanded = actionsMenuExpanded,
            onDismissRequest = { actionsMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_actions_edit)) },
                onClick = {
                    actionsMenuExpanded = false
                    onEdit()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_actions_delete)) },
                onClick = {
                    actionsMenuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_forever),
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_actions_copy)) },
                onClick = {
                    actionsMenuExpanded = false
                    onCopy()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = null,
                    )
                },
            )
        }
    }
}
