package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

// Categorizes every spend in the transactions table that has no persisted category and saves
// the assignment to the DB: the offline keyword classifier first (instant, deterministic, no
// AI needed), then the AI model for whatever the keywords couldn't place. Persisting means
// the analytics category breakdown is stable and no AI reload is ever required.
@HiltViewModel
class SpendCategoriesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
) : ViewModel() {

    private val _isCategorizing = MutableLiveData(false)
    val isCategorizing: LiveData<Boolean> = _isCategorizing

    fun categorizeUncategorized() {
        viewModelScope.launch {
            if (_isCategorizing.value == true) return@launch
            _isCategorizing.value = true
            try {
                val uncategorized = transactionDao.getAllNow()
                    .filter { it.type == TransactionType.SPENT && it.category.isNullOrBlank() }
                if (uncategorized.isEmpty()) return@launch

                val offlineAssigned = uncategorized.mapNotNull { transaction ->
                    offlineCategoryOrNull(transaction.comment)
                        ?.let { transaction.uid to it.name }
                }
                offlineAssigned.forEach { (uid, category) ->
                    transactionDao.updateCategory(uid, category)
                }

                val aiCandidates = uncategorized.filter {
                    offlineCategoryOrNull(it.comment) == null
                }
                if (aiCandidates.isEmpty()) return@launch

                val assigned = categorizeSpendsWithAi(context, aiCandidates)
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
