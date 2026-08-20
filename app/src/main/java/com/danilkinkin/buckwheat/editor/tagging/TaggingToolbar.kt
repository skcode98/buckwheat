package com.danilkinkin.buckwheat.editor.tagging

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.editor.EditStage
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.editor.FocusController
import com.danilkinkin.buckwheat.patterns.TagSuggestion

@Composable
fun TaggingToolbar(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
    editorFocusController: FocusController
) {
    val localDensity = LocalDensity.current

    val tags by spendsViewModel.tags.collectAsStateWithLifecycle(emptyList())
    val tagSuggestions by spendsViewModel.tagSuggestions.collectAsStateWithLifecycle(emptyList())
    val currentComment by editorViewModel.currentComment.collectAsStateWithLifecycle("")

    var showAddComment by remember { mutableStateOf(false) }
    var isEdit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        editorViewModel.stage.collect {
            showAddComment = it === EditStage.EDIT_SPENT
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth - 48.dp

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(44.dp)
                .horizontalScroll(
                    state = rememberScrollState(),
                    enabled = !isEdit,
                    reverseScrolling = true,
                )
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (showAddComment && tagSuggestions.isNotEmpty()) {
                tagSuggestions.take(3).forEach { suggestion ->
                    val isSelected = suggestion.tag == currentComment
                    TagSuggestionChip(
                        tag = suggestion.tag,
                        reasonRes = suggestion.reasonRes,
                        reasonArgs = suggestion.reasonArgs,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelected) {
                                editorViewModel.currentComment.value = ""
                            } else {
                                editorViewModel.currentComment.value = suggestion.tag
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            tags.take(5).reversed().forEach { tag ->
                val isSelected = tag == currentComment
                AnimatedVisibility(
                    visible = showAddComment,
                    enter = fadeIn(
                        tween(
                            durationMillis = 150,
                            easing = EaseInOutQuad,
                        )
                    ) + slideInHorizontally(
                        tween(
                            durationMillis = 150,
                            easing = EaseInOutQuad,
                        )
                    ) { with(localDensity) { 30.dp.toPx().toInt() } },
                    exit = fadeOut(
                        tween(
                            durationMillis = 150,
                            easing = EaseInOutQuad,
                        )
                    ) + slideOutHorizontally(
                        tween(
                            durationMillis = 150,
                            easing = EaseInOutQuad,
                        )
                    ) { with(localDensity) { 30.dp.toPx().toInt() } },
                ) {
                    Tag(value = tag, onClick = {
                        if (isSelected) {
                            editorViewModel.currentComment.value = ""
                        } else {
                            editorViewModel.currentComment.value = tag
                        }
                    })
                }
            }
            Spacer(modifier = Modifier.width(24.dp))
            AnimatedVisibility(
                visible = showAddComment,
                enter = fadeIn(
                    tween(
                        durationMillis = 150,
                        easing = EaseInOutQuad,
                    )
                ) + slideInHorizontally(
                    tween(
                        durationMillis = 150,
                        easing = EaseInOutQuad,
                    )
                ) { with(localDensity) { 30.dp.toPx().toInt() } },
                exit = fadeOut(
                    tween(
                        durationMillis = 150,
                        easing = EaseInOutQuad,
                    )
                ) + slideOutHorizontally(
                    tween(
                        durationMillis = 150,
                        easing = EaseInOutQuad,
                    )
                ) { with(localDensity) { 30.dp.toPx().toInt() } },
            ) {
                CustomTag(
                    onlyIcon = tags.isNotEmpty(),
                    editorFocusController = editorFocusController,
                    extendWidth = width,
                    onEdit = { isEdit = it },
                )
            }
        }
    }
}

@Composable
private fun TagSuggestionChip(
    tag: String,
    reasonRes: Int,
    reasonArgs: List<Any>,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}