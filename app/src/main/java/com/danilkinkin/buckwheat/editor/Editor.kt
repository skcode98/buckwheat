package com.danilkinkin.buckwheat.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.data.entities.SpendType
import com.danilkinkin.buckwheat.editor.category.CategoryPillsRow
import com.danilkinkin.buckwheat.editor.dateTimeEdit.DateTimeEditPill
import com.danilkinkin.buckwheat.editor.tagging.TaggingToolbar
import com.danilkinkin.buckwheat.editor.toolbar.EditorToolbar
import com.danilkinkin.buckwheat.ui.BuckwheatTheme

enum class AnimState { EDITING, COMMIT, IDLE, RESET }

@Composable
fun Editor(
    modifier: Modifier = Modifier,
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    appViewModel: AppViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
    onOpenHistory: (() -> Unit)? = null,
) {
    val focusController = remember { FocusController() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusController.focus() }
        ) {
            EditorToolbar()
            DateTimeEditPill()
            CurrentSpendEditor(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                focusController = focusController,
            )
            SpendTypeToggle(editorViewModel = editorViewModel)
            CategoryPillsRow()
            TaggingToolbar(editorFocusController = focusController)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpendTypeToggle(
    editorViewModel: EditorViewModel = hiltViewModel(),
) {
    val currentType by editorViewModel.currentSpendType.observeAsState(SpendType.WANTS)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        SpendType.entries.forEach { type ->
            FilterChip(
                selected = currentType == type,
                onClick = { editorViewModel.currentSpendType.value = type },
                label = {
                    Text(
                        text = type.name,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (type == SpendType.NEEDS)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Preview
@Composable
fun EditorPreview() {
    BuckwheatTheme {
        Editor()
    }
}
