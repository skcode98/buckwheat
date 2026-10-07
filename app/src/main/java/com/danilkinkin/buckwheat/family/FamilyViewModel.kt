package com.danilkinkin.buckwheat.family

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.FamilySyncCoordinator
import com.danilkinkin.buckwheat.util.DAY
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class PeriodRange(
    val start: Date,
    val endInclusive: Date?,
) {
    /** The day count a "per day" average is divided by. Never zero. */
    val periodDays: Long
        get() = (maxOf(endInclusive?.time ?: start.time, start.time) - start.time) / DAY.coerceAtLeast(1)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val familyTransactionDao: FamilyTransactionDao,
    private val sessionStore: FamilySessionStore,
    private val spendsRepository: FamilyPeriodSource,
    private val coordinator: FamilySyncCoordinator? = null,
    @ApplicationContext private val context: Context? = null,
) : ViewModel() {

    private val ZERO = BigDecimal.ZERO

    val session: StateFlow<FamilySession?> = sessionStore.session()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val members: StateFlow<List<FamilyMember>> = sessionStore.members()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val period: StateFlow<PeriodRange> = combine(
        spendsRepository.getStartPeriodDate(),
        spendsRepository.getFinishPeriodDate(),
    ) { start, finish ->
        PeriodRange(start = start, endInclusive = finish)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PeriodRange(Date(0), Date(0)))

    internal val rows: StateFlow<List<FamilyTransaction>> = period
        .flatMapLatest { range ->
            flow {
                emit(familyTransactionDao.getAllInPeriod(range.start, range.endInclusive ?: Date(Long.MAX_VALUE)))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val familyTotal: StateFlow<BigDecimal> = rows
        .map { inPeriodRows -> inPeriodRows.fold(ZERO) { acc, tx -> acc + tx.value } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ZERO)

    val ownSpend: StateFlow<BigDecimal> = combine(rows, session) { inPeriodRows, s ->
        val viewerId = s?.memberId
        inPeriodRows.fold(ZERO) { acc, tx -> if (tx.memberId == viewerId) acc + tx.value else acc }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ZERO)

    val spendByMember: StateFlow<Map<String, BigDecimal>> = rows.map { inPeriodRows ->
        inPeriodRows.fold(mutableMapOf<String, BigDecimal>()) { acc, tx ->
            val member = tx.memberId
            if (member != null) acc[member] = (acc[member] ?: ZERO) + tx.value
            acc
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val transactionCount: StateFlow<Int> = rows
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _rosterLoading = MutableStateFlow(false)
    val rosterLoading: StateFlow<Boolean> = _rosterLoading.asStateFlow()

    private val _rosterFailed = MutableStateFlow(false)
    val rosterFailed: StateFlow<Boolean> = _rosterFailed.asStateFlow()

    init {
        viewModelScope.launch {
            if (session.value != null && members.value.isEmpty()) syncNow()
        }
    }

    fun refresh() {
        val target = coordinator ?: return
        viewModelScope.launch {
            _rosterLoading.value = true
            _rosterFailed.value = runCatching { target.members() }.isFailure
            _rosterLoading.value = false
        }
    }

    fun syncNow() {
        coordinator?.let { target ->
            viewModelScope.launch { target.syncNow() }
        }
    }

    fun leave() {
        coordinator?.let { target ->
            viewModelScope.launch { target.leave() }
        }
    }

    fun disconnect() {
        coordinator?.let { target ->
            viewModelScope.launch { target.signOut() }
        }
    }
}