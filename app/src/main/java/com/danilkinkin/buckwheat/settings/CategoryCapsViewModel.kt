package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class CategoryCapsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val caps: LiveData<Map<String, BigDecimal>> =
        settingsRepository.getCategoryCaps().asLiveData()

    // Schedules merged with their cap amounts: a category with an entry here is an
    // interleaved budget (window-scoped, auto-rolls), one without stays a plain cap.
    val interleaved: LiveData<Map<String, InterleavedCategory>> =
        settingsRepository.getInterleavedCategories().asLiveData()

    // `amount == null` or non-positive clears the cap for the category.
    fun setCap(name: String, amount: BigDecimal?) {
        viewModelScope.launch {
            val current = settingsRepository.getCategoryCaps().first()
            settingsRepository.setCategoryCaps(
                if (amount == null || amount <= BigDecimal.ZERO) current - name
                else current + (name to amount)
            )
        }
    }

    // Sets (or replaces) the schedule for a category: frequency + window anchor, caps
    // untouched. DAILY means "plain cap" so the schedule entry is removed instead.
    fun setInterleaved(name: String, frequency: CategoryFrequency, anchorEpochDay: Long) {
        viewModelScope.launch {
            val caps = settingsRepository.getCategoryCaps().first()
            val schedules = settingsRepository.getCategorySchedules().first()
            settingsRepository.setCategoryCapsAndSchedules(
                caps,
                if (frequency == CategoryFrequency.DAILY) schedules - name
                else schedules + (name to InterleavedCategory(
                    name = name,
                    amount = caps[name] ?: BigDecimal.ZERO,
                    frequency = frequency,
                    anchorEpochDay = anchorEpochDay,
                ))
            )
        }
    }

    // Moves only the window anchor, keeping the category's frequency and amount.
    fun setAnchor(name: String, anchorEpochDay: Long) {
        viewModelScope.launch {
            val caps = settingsRepository.getCategoryCaps().first()
            val schedules = settingsRepository.getCategorySchedules().first()
            val schedule = schedules[name] ?: return@launch
            settingsRepository.setCategoryCapsAndSchedules(
                caps,
                schedules + (name to schedule.copy(anchorEpochDay = anchorEpochDay))
            )
        }
    }

    // Applies a quick template: schedules each built-in category at `frequency` with the
    // window anchored to today, seeding a default cap amount where the category has none
    // (a zero-amount schedule can never cross a cap, so templates always start usable).
    fun applyTemplate(
        frequency: CategoryFrequency,
        names: List<String>,
        defaultAmount: BigDecimal,
    ) {
        viewModelScope.launch {
            val caps = settingsRepository.getCategoryCaps().first()
            val schedules = settingsRepository.getCategorySchedules().first()
            val today = LocalDate.now().toEpochDay()
            val seededCaps = names.fold(caps) { acc, name ->
                if (name in acc) acc else acc + (name to defaultAmount)
            }
            val newSchedules = names
                .filter { name -> SpendCategory.fromStored(name) != null }
                .associateWith { name ->
                    InterleavedCategory(
                        name = name,
                        amount = seededCaps[name] ?: defaultAmount,
                        frequency = frequency,
                        anchorEpochDay = today,
                    )
                }
            settingsRepository.setCategoryCapsAndSchedules(seededCaps, schedules + newSchedules)
        }
    }
}
