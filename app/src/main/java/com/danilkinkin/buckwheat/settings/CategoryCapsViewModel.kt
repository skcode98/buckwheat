package com.danilkinkin.buckwheat.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.budgetStoreKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class CategoryCapsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    // Fills a category's cap with the current budget amount so the user has a sensible
    // starting point to edit. Does nothing when the budget is not set (zero).
    fun setAutoCap(name: String) {
        viewModelScope.launch {
            val budget = context.budgetDataStore.data.first()[budgetStoreKey]
                ?.toBigDecimalOrNull() ?: return@launch
            if (budget > BigDecimal.ZERO) {
                setCap(name, budget)
            }
        }
    }
}
