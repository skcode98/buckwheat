package com.danilkinkin.buckwheat.editor

import androidx.lifecycle.*
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

    var editedTransaction: Transaction? = null
    var currentDate: Date = Date()
    var currentSpent: BigDecimal = BigDecimal.ZERO
    var currentComment = MutableLiveData("")
    var rawSpentValue = MutableLiveData("")
    var currentCategory = MutableLiveData<String?>(null)

    fun startEditingSpent(transaction: Transaction) {
        editedTransaction = transaction
        currentSpent = transaction.value
        currentDate = transaction.date
        currentComment.value = transaction.comment
        rawSpentValue.value = tryConvertStringToNumber(transaction.value.toString()).join(third = false)
        currentCategory.value = transaction.category

        stage.value = EditStage.EDIT_SPENT
        mode.value = EditMode.EDIT
    }

    fun startCreatingSpent() {
        currentSpent = BigDecimal.ZERO
        currentCategory.value = null

        stage.value = EditStage.CREATING_SPENT
    }

    // Prefill the editor with a previous transaction so the user can re-add it with one more
    // tap (the "repeat last spend" quick action). Uses today's date by default; the user can
    // still change it via the date pill before committing.
    fun startRepeatSpend(transaction: Transaction) {
        currentSpent = transaction.value
        currentDate = Date()
        currentComment.value = transaction.comment
        rawSpentValue.value = tryConvertStringToNumber(transaction.value.toString()).join(third = false)
        currentCategory.value = transaction.category

        editedTransaction = null
        mode.value = EditMode.ADD
        stage.value = EditStage.EDIT_SPENT
    }

    fun modifyEditingSpent(value: BigDecimal) {
        currentSpent = value

        stage.value = EditStage.EDIT_SPENT
    }

    fun resetEditingSpent() {
        currentSpent = BigDecimal.ZERO
        currentDate = Date()
        currentComment.value = ""
        rawSpentValue.value = ""
        currentCategory.value = null

        stage.value = EditStage.IDLE
        mode.value = EditMode.ADD
        editedTransaction = null
    }

    fun canCommitEditingSpent(): Boolean {
        if (stage.value !== EditStage.EDIT_SPENT) return false

        val formatSpent = currentSpent
            .setScale(2, RoundingMode.HALF_EVEN)
            .stripTrailingZeros()
            .toPlainString()

        return formatSpent != "0"
    }
}