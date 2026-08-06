package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

// Drives the analytics-only AI categorization. On Analytics open it batch-assigns the
// predefined categories to every spend that has none yet and persists them to the
// transactions table; display falls back to the offline keyword classifier until then.
@HiltViewModel
class SpendCategoriesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
) : ViewModel() {

    private val _isCategorizing = MutableLiveData(false)
    val isCategorizing: LiveData<Boolean> = _isCategorizing

    fun categorizeUncategorized(spends: List<Transaction>) {
        val uncategorized = spends.filter { it.category.isNullOrBlank() }
        if (uncategorized.isEmpty()) return

        viewModelScope.launch {
            if (_isCategorizing.value == true) return@launch
            _isCategorizing.value = true
            try {
                val assigned = categorizeSpendsWithAi(context, uncategorized)
                assigned.forEach { (uid, category) ->
                    transactionDao.updateCategory(uid, category.name)
                }
            } catch (e: Exception) {
                Log.d("SpendCategories", "AI categorization failed", e)
            } finally {
                _isCategorizing.value = false
            }
        }
    }
}
