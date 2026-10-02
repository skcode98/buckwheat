package com.danilkinkin.buckwheat.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.FamilyStateDao
import com.danilkinkin.buckwheat.data.dao.PeriodLimitDao
import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.sync.FamilyMembersCache
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the family budget sheet.
 *
 * Nothing here touches the network. The roster comes from [FamilyMembersCache], which a sync run
 * populates and which is readable offline, so the sheet renders for a family whose server is asleep.
 * That is the same contract the local budget has: family data is an addition to a screen that works
 * regardless, never a precondition for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FamilyBudgetViewModel @Inject constructor(
    private val familyStateDao: FamilyStateDao,
    private val periodLimitDao: PeriodLimitDao,
    private val spendsRepository: SpendsRepository,
    private val sessionStore: FamilySessionStore,
    private val membersCache: FamilyMembersCache,
) : ViewModel() {

    val session = sessionStore.session()

    val members: StateFlow<Map<String, String>> = membersCache.members()
        .map { list -> list.associate { it.id to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Null until a period is configured, which is also the state in which there is nothing to show. */
    private val periodBounds: StateFlow<Pair<Long, Long>?> =
        combine(
            spendsRepository.getStartPeriodDate(),
            spendsRepository.getFinishPeriodDate(),
        ) { start, finish ->
            if (finish == null) null else start.time to finish.time
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val budget: StateFlow<FamilyBudget?> =
        combine(periodBounds, session) { bounds, session -> bounds to session }
            .flatMapLatest { (bounds, session) ->
                if (bounds == null || session == null) {
                    flowOf(null)
                } else {
                    combine(
                        familyStateDao.observe(session.familyId),
                        periodLimitDao.observeForPeriod(poolPeriodId(bounds.first)),
                        spendsRepository.spentByMemberCurrentPeriodFlow(members.map { it }),
                        spendsRepository.householdSpentCurrentPeriodFlow(),
                    ) { state, limits, spent, household ->
                        if (state == null) {
                            null
                        } else {
                            familyBudget(
                                state = state,
                                limits = limits,
                                spentByMember = spent.mapNotNull { memberSpend ->
                                    memberSpend.memberId?.let { MemberAmount(it, memberSpend.total) }
                                },
                                householdSpent = household,
                            )
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Only the owner may change the split; everyone else sees a read-only breakdown. */
    val isHead: StateFlow<Boolean> =
        combine(session, membersCache.members()) { session, roster ->
            if (session == null) {
                false
            } else {
                roster.firstOrNull { it.id == session.memberId }?.isOwner == true
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun displayName(memberId: String): String = members.value[memberId] ?: memberId

    private val _saveProblem = MutableStateFlow<AllocationProblem?>(null)

    /** Null means the last save was accepted. Surfaced as a state rather than thrown. */
    val saveProblem: StateFlow<AllocationProblem?> = _saveProblem

    /**
     * The editor's single entry point: validates, writes the pool and the slices together, then
     * applies the split rule only if the write succeeded. Applying the rule first would leave a
     * rejected split sitting under a new rule, so the screen and the arithmetic would disagree.
     */
    fun save(
        pool: BigDecimal,
        household: BigDecimal,
        allocations: Map<String, BigDecimal>,
        rule: CommonSplitRule,
    ) {
        viewModelScope.launch {
            val problem = setPool(pool, household, allocations)
            if (problem == null) setSplitRule(rule)
            _saveProblem.value = problem
        }
    }

    /**
     * Records the pool and the split together, or neither.
     *
     * Validated before anything is written and rejected rather than clamped: a partially allocated
     * pool is the one state a member cannot reason about, because the pool and the slices would
     * disagree and both numbers would be on screen at once.
     *
     * Returns the reason rather than throwing, because the editor has to say which of the two the
     * person meant, and a thrown exception cannot carry that choice.
     */
    suspend fun setPool(
        total: BigDecimal,
        householdTier: BigDecimal,
        allocations: Map<String, BigDecimal>,
    ): AllocationProblem? {
        val session = sessionStore.current() ?: return AllocationProblem.NO_ALLOCATION
        val bounds = periodBounds.value ?: return AllocationProblem.NO_ALLOCATION
        if (allocations.isEmpty()) return AllocationProblem.NO_ALLOCATION
        if (total.signum() < 0 || householdTier.signum() < 0) return AllocationProblem.NEGATIVE_ALLOCATION
        if (householdTier > total) return AllocationProblem.OVER_ALLOCATED

        val periodId = poolPeriodId(bounds.first)
        val candidate = allocations.map { (memberId, value) ->
            PeriodLimit(periodId = periodId, memberId = memberId, limitValue = value)
        }
        val problem = validateAllocations(candidate, total.subtract(householdTier), allocations.size)
        if (problem != null) return problem

        val existing = familyStateDao.getByFamilyId(session.familyId)
        familyStateDao.upsert(
            FamilyState(
                familyId = session.familyId,
                budget = total,
                householdTier = householdTier,
                startDate = bounds.first,
                finishDate = bounds.second,
                currency = existing?.currency.orEmpty(),
                householdDetailVisibleToAll = existing?.householdDetailVisibleToAll ?: false,
                commonSplitRule = existing?.commonSplitRule ?: CommonSplitRule.EQUAL.name,
                tagsVisibleToSelf = existing?.tagsVisibleToSelf ?: true,
                familyAiEnabled = existing?.familyAiEnabled ?: true,
            )
        )

        val stored = periodLimitDao.getForPeriod(periodId)
        val keep = candidate.map { limit ->
            limit.copy(
                // Reuse the stored id so the unique (periodId, memberId) index updates in place
                // instead of the insert failing against a row that already exists.
                id = stored.firstOrNull { it.memberId == limit.memberId }?.id ?: limit.id,
                familyId = session.familyId,
            )
        }
        val keepIds = keep.map { it.memberId }.toSet()
        stored.filterNot { it.memberId in keepIds }.forEach { periodLimitDao.deleteById(it.id) }
        keep.forEach { periodLimitDao.upsert(it) }
        return null
    }

    fun setHouseholdTier(tier: BigDecimal) {
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
            familyStateDao.upsert(
                current.copy(
                    householdTier = tier,
                    budget = tier.add(current.memberTier),
                )
            )
        }
    }

    fun setSplitRule(rule: CommonSplitRule) {
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
            familyStateDao.upsert(current.copy(commonSplitRule = rule.name))
        }
    }

    fun setHouseholdDetailVisibleToAll(visible: Boolean) {
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
            familyStateDao.upsert(current.copy(householdDetailVisibleToAll = visible))
        }
    }
}