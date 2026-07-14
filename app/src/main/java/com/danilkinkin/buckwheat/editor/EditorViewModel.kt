package com.danilkinkin.buckwheat.editor

import androidx.lifecycle.*
import com.danilkinkin.buckwheat.data.entities.SpendType
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.util.join
import com.danilkinkin.buckwheat.util.tryConvertStringToNumber
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import javax.inject.Inject

enum class EditMode { ADD, EDIT }
enum class EditStage { IDLE, CREATING_SPENT, EDIT_SPENT, COMMITTING_SPENT }

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    var mode = MutableLiveData(EditMode.ADD)
    var stage = MutableLiveData(EditStage.IDLE)

    var editedTransaction = MutableLiveData<Transaction?>(null)
    var currentDate = MutableLiveData(Date())
    var currentSpent = MutableLiveData(BigDecimal.ZERO)
    var currentComment = MutableLiveData("")
    var currentSpendType = MutableLiveData(SpendType.WANTS)
    var currentCategoryId = MutableLiveData<Long?>(null)
    var rawSpentValue = MutableLiveData("")

    fun startEditingSpent(transaction: Transaction) {
        editedTransaction.value = transaction
        currentSpent.value = transaction.value
        currentDate.value = transaction.date
        currentComment.value = transaction.comment
        currentSpendType.value = transaction.spendType
        currentCategoryId.value = transaction.categoryId
        rawSpentValue.value = tryConvertStringToNumber(transaction.value.toString()).join(third = false)

        stage.value = EditStage.EDIT_SPENT
        mode.value = EditMode.EDIT
    }

    fun startCreatingSpent() {
        currentSpent.value = BigDecimal.ZERO

        stage.value = EditStage.CREATING_SPENT
    }

    fun modifyEditingSpent(value: BigDecimal) {
        currentSpent.value = value

        stage.value = EditStage.EDIT_SPENT
    }

    fun resetEditingSpent(keepMeta: Boolean = false) {
        currentSpent.value = BigDecimal.ZERO
        if (!keepMeta) {
            currentDate.value = Date()
            currentComment.value = ""
            currentSpendType.value = SpendType.WANTS
            currentCategoryId.value = null
        }
        rawSpentValue.value = ""

        stage.value = EditStage.IDLE
        mode.value = EditMode.ADD
        editedTransaction.value = null
    }

    fun canCommitEditingSpent(): Boolean {
        if (stage.value !== EditStage.EDIT_SPENT) return false

        val spent = currentSpent.value ?: BigDecimal.ZERO
        val formatSpent = spent
            .setScale(2, RoundingMode.HALF_EVEN)
            .stripTrailingZeros()
            .toPlainString()

        return formatSpent != "0"
    }
}