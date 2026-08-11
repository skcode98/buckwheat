package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.base.datePicker.DatePicker
import com.danilkinkin.buckwheat.base.datePicker.model.CalendarState
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.toDate
import java.time.LocalDate

const val INTERLEAVED_ANCHOR_SHEET = "interleavedAnchor"

// Single-day date picker that moves the window anchor of one interleaved category. The
// anchor is the first window start; every frequency period counts from it.
@Composable
fun InterleavedAnchorSheet(
    categoryName: String,
    anchorEpochDay: Long,
    onClose: () -> Unit,
    capsViewModel: CategoryCapsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val initialAnchor = remember(anchorEpochDay) { LocalDate.ofEpochDay(anchorEpochDay) }
    val calendarState = remember {
        CalendarState(
            context,
            selectDate = initialAnchor.toDate(),
        )
    }

    Surface(Modifier.fillMaxSize().padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.interleaved_anchor_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${SpendCategory.emojiFor(categoryName, "")}  " +
                        categoryDisplayName(categoryName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Button(
                    onClick = {
                        calendarState.calendarUiState.value.selectedStartDate
                            ?.toEpochDay()
                            ?.let { capsViewModel.setAnchor(categoryName, it) }
                        onClose()
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_apply),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(R.string.apply))
                }
            }
            DatePicker(
                calendarState = calendarState,
                onDayClicked = { calendarState.setSelectedDay(it) },
            )
        }
    }
}

@Preview
@Composable
private fun PreviewInterleavedAnchor() {
    BuckwheatTheme {
        InterleavedAnchorSheet(
            categoryName = "FOOD",
            anchorEpochDay = LocalDate.now().toEpochDay(),
            onClose = {},
        )
    }
}
