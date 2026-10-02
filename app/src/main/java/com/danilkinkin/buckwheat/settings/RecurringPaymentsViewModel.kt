package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class RecurringPaymentsViewModel @Inject constructor(
    private val recurringDao: RecurringDao,
    private val settingsRepository: SettingsRepository,
    private val syncDirtyMarker: SyncDirtyMarker,
) : ViewModel() {
    val templates: StateFlow<List<RecurringTemplate>> = recurringDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val autoApplyMode: StateFlow<RecurringAutoApplyMode> =
        settingsRepository.getRecurringAutoApplyMode()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecurringAutoApplyMode.SILENT)

    fun setAutoApplyMode(mode: RecurringAutoApplyMode) {
        viewModelScope.launch {
            settingsRepository.setRecurringAutoApplyMode(mode)
        }
    }

    fun addTemplate(amount: BigDecimal, comment: String, dayOfMonth: Int) {
        if (amount <= BigDecimal.ZERO || comment.isBlank() || dayOfMonth !in 1..31) return
        viewModelScope.launch {
            val template = RecurringTemplate(
                amount = amount,
                comment = comment.trim(),
                dayOfMonth = dayOfMonth,
            )
            recurringDao.insert(template)
            syncDirtyMarker.markUpsert(SyncTables.RECURRING_TEMPLATES, template.id)
        }
    }

    fun toggleEnabled(template: RecurringTemplate) {
        viewModelScope.launch {
            // Same hazard as updateTemplate: @Update writes every column, so flipping a field on the
            // caller's stale snapshot silently nulls family_id/sync_seq and severs the template from
            // the family. Toggling is the cheapest operation a user performs and it must not cost them
            // their place in the family budget.
            val stored = recurringDao.getById(template.id) ?: template
            recurringDao.update(stored.copy(enabled = !stored.enabled))
            syncDirtyMarker.markUpsert(SyncTables.RECURRING_TEMPLATES, template.id)
        }
    }

    fun updateTemplate(template: RecurringTemplate, amount: BigDecimal, comment: String, dayOfMonth: Int) {
        if (amount <= BigDecimal.ZERO || comment.isBlank() || dayOfMonth !in 1..31) return
        viewModelScope.launch {
            // The caller owns only amount/comment/dayOfMonth. `template` is the list snapshot the
            // UI holds, so a pull can have enrolled the row since: read the sync-owned columns back
            // from the row, because @Update writes every column and markUpsert never restores them.
            val stored = recurringDao.getById(template.id) ?: template
            recurringDao.update(
                stored.copy(
                    amount = amount,
                    comment = comment.trim(),
                    dayOfMonth = dayOfMonth,
                )
            )
            syncDirtyMarker.markUpsert(SyncTables.RECURRING_TEMPLATES, template.id)
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            val existing = recurringDao.getAllNow().firstOrNull { it.id == id } ?: return@launch
            recurringDao.deleteById(existing.id)
            syncDirtyMarker.markDelete(
                SyncTables.RECURRING_TEMPLATES,
                existing.id,
                existing.familyId,
                existing.syncSeq,
            )
        }
    }
}
