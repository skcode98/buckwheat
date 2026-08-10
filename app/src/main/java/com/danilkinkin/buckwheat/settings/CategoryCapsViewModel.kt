package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class CategoryCapsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val caps: LiveData<Map<String, BigDecimal>> =
        settingsRepository.getCategoryCaps().asLiveData()

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
}
