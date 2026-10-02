package com.danilkinkin.buckwheat.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.FamilyStateDao
import com.danilkinkin.buckwheat.data.dao.PeriodLimitDao
import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.asHouseholdSpend
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import com.danilkinkin.buckwheat.sync.FamilyMembersCache
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val insightService: FamilyInsightService,
    private val dirtyMarker: SyncDirtyMarker,
) : ViewModel() {

    val session = sessionStore.session()

    /**
     * The currency every figure on this screen is shown in.
     *
     * Read through the repository rather than the device locale, because the user's own budget can be
     * in a currency that has nothing to do with where their phone is set -- and a shared family pool
     * may well be. Formatting these amounts from the locale would show a household's rent in a
     * currency nobody in it uses.
     */
    val currency: StateFlow<ExtendCurrency> = spendsRepository.getCurrency()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtendCurrency.none())

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

    /**
     * The key allocations and requests are stored under, or null when there is no active period.
     *
     * Exposed because a request has to be filed against the same period as the split it draws on, and
     * recomputing it in the sheet would mean two copies of the derivation that must agree.
     */
val activePeriodKey: String?
        get() = periodBounds.value?.let { poolPeriodId(it.first) }

    /**
     * A key that changes when the active period does, for an effect that should re-run then.
     *
     * Distinct from [activePeriodKey] on purpose: that is the id allocations are stored under and
     * belongs in a payload, whereas this only has to change. Exposing the raw id invites using it as
     * the key, and a key that is also data is one somebody will accidentally send.
     */
    val periodKey: StateFlow<String?> = periodBounds
        .map { bounds -> bounds?.let { "${it.first}:${it.second}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * A spending tag per member, for the current period.
     *
     * Derived on every emission rather than stored, so there is nothing to leak, nothing to sync and
     * nothing to invalidate. Cheap because it is a handful of multiplications over rows already loaded.
     */
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
                                    memberSpend.memberId?.let { MemberAmount(it, memberSpend.total, memberSpend.transactionCount) }
                                },
                                householdSpent = household,
                            )
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * A spending tag per member, for the current period.
     *
     * Derived on every emission rather than stored, so there is nothing to leak, nothing to sync and
     * nothing to invalidate. Cheap because it is a handful of multiplications over rows already loaded.
     *
     * Declared after [budget] because a property initializer cannot read a property declared later in
     * the class, and there is no reason for it to come first.
     */
    val tags: StateFlow<Map<String, MemberTag>> =
        combine(periodBounds, budget) { bounds, budget ->
            if (bounds == null || budget == null) {
                emptyMap()
            } else {
                val progress = periodProgress(bounds.first, bounds.second, System.currentTimeMillis())
                memberTags(
                    // Previous-period spend is set equal to the current figure, which makes the
                    // rising-spend check unable to fire. Claiming a trend needs history this does not
                    // have, and a tag that is always right for the wrong reason is worse than none.
                    spendings = budget.allocations.map { allocation ->
                        MemberSpending(
                            memberId = allocation.memberId,
                            transactionCount = allocation.transactionCount,
                            spent = allocation.spent,
                            previousSpent = allocation.spent,
                        )
                    },
                    allocations = budget.allocations,
                    progress = progress,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Only the owner may change the split; everyone else sees a read-only breakdown. */
    val isHead: StateFlow<Boolean> =
        combine(session, membersCache.members()) { session, roster ->
            if (session == null) {
                false
            } else {
                roster.firstOrNull { it.id == session.memberId }?.isOwner == true
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Whether an individual household expense may be seen by everyone rather than only by the head.
     *
     * Read from the stored pool rather than held locally, because it is a family-wide setting and two
     * devices that disagreed about it would show one member's rent to another.
     */
    val householdDetailVisibleToAll: StateFlow<Boolean> =
        combine(session, budget) { session, budget -> session != null && budget != null }
            .flatMapLatest { hasBudget ->
                if (!hasBudget) {
                    flowOf(false)
                } else {
                    combine(session, membersCache.members()) { s, _ -> s?.familyId }
                        .distinctUntilChanged()
                        .flatMapLatest { familyId ->
                            // Typed null rather than a bare null, or the if-expression widens to
                            // Flow<Any?> and every later receiver becomes Any?.
                            if (familyId == null) flowOf<FamilyState?>(null)
                            else familyStateDao.observe(familyId)
                        }
                        .map { it?.householdDetailVisibleToAll == true }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The individual household rows, for the sheet to decide whether to show them.
     *
     * Always emitted, and the decision to reveal is made at the sheet. The alternative — filtering in
     * the query — means the permission check and the display rule live apart and can drift, and the
     * head's own list would flicker empty on a slow frame.
     */
    val householdRows: StateFlow<List<Transaction>> =
        periodBounds.flatMapLatest { bounds ->
            if (bounds == null) {
                flowOf(emptyList())
            } else {
                spendsRepository.householdSpendsInPeriod(bounds.first, bounds.second)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records a common household expense such as rent or insurance.
     *
     * Gated on the owner because it spends from the shared tier, and re-bucketed through
     * [asHouseholdSpend] rather than setting the two fields here, so the rule that a household row
     * carries no member lives in exactly one place.
     */
    fun addHouseholdSpend(amount: BigDecimal, comment: String, category: String?, date: Date): Boolean {
        if (!isHead.value) return false
        if (amount.signum() <= 0) return false
        viewModelScope.launch {
            // `session` is a plain Flow, so it has no `.value`; the suspending read is correct here and
            // is also the right thing to want: it sees an enrolment that completed a moment ago.
            val familyId = sessionStore.current()?.familyId
spendsRepository.addSpent(
                Transaction(
                    type = TransactionType.SPENT,
                    value = amount,
                    date = date,
                    comment = comment.trim(),
                    category = category,
                    familyId = familyId,
                ).asHouseholdSpend()
            )
        }
        return true
    }

    fun removeHouseholdSpend(transaction: Transaction) {
        if (!isHead.value) return
        viewModelScope.launch { spendsRepository.removeSpent(transaction) }
    }

    fun displayName(memberId: String): String = members.value[memberId] ?: memberId

    private val _summary = MutableStateFlow<FamilySummary?>(null)

    /**
     * The family summary, or null before the screen asks for one.
     *
     * Rendered from the offline text first and upgraded in place, so a family with no key, no network
     * or AI switched off still sees real content instead of a spinner. `fromModel` is carried so the
     * sheet can say which one it is showing: a person told their household is doing badly deserves to
     * know whether a model wrote that.
     */
    val summary: StateFlow<FamilySummary?> = _summary

    private val _summaryLoading = MutableStateFlow(false)
    val summaryLoading: StateFlow<Boolean> = _summaryLoading

    /** Asking twice in a row is cheap and harmless, and the offline half means it never blocks. */
    fun loadSummary() {
        val bounds = periodBounds.value ?: return
        val current = budget.value ?: return
        if (_summaryLoading.value) return

        val snapshot = buildFamilySnapshot(
            budget = current,
            memberIdsInRosterOrder = members.value.keys.toList(),
            tags = tags.value,
        )
        _summary.value = FamilySummary(offlineFamilySummary(snapshot), fromModel = false)
        _summaryLoading.value = true
        viewModelScope.launch {
            val familyAiEnabled = familyStateDao.getByFamilyId(sessionStore.current()?.familyId.orEmpty())
                ?.familyAiEnabled != false
            _summary.value = insightService.summarise(snapshot, familyAiEnabled)
            _summaryLoading.value = false
        }
    }

    /**
 * The split rule currently on the pool, so the editor opens showing what is actually set rather than
 * resetting a family that chose proportional back to equal on every visit.
 */
    // Declared before splitRule below, which reads it: a property initializer cannot reference a
    // property declared further down.
    private val storedPool: StateFlow<FamilyState?> =
        combine(session, budget) { s, _ -> s?.familyId }
            .distinctUntilChanged()
            .flatMapLatest { familyId ->
                if (familyId == null) flowOf<FamilyState?>(null) else familyStateDao.observe(familyId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val splitRule: StateFlow<CommonSplitRule> = combine(session, storedPool) { s, pool ->
        if (s == null || pool == null) CommonSplitRule.EQUAL else pool.splitRule
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommonSplitRule.EQUAL)


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
        // The server refuses a non-owner anyway, but a rule that lives only in the UI is not a rule.
        if (!isHead.value) return AllocationProblem.NO_ALLOCATION
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
            ),
        )
        dirtyMarker.markUpsert(SyncTables.FAMILY_STATE, session.familyId)

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
        stored.filterNot { it.memberId in keepIds }.forEach { stale ->
            periodLimitDao.deleteById(stale.id)
            dirtyMarker.markDelete(SyncTables.PERIOD_LIMITS, stale.id, session.familyId, stale.syncSeq)
        }
        keep.forEach { limit ->
            periodLimitDao.upsert(limit)
            dirtyMarker.markUpsert(SyncTables.PERIOD_LIMITS, limit.id)
        }
        dirtyMarker.markUpsert(SyncTables.FAMILY_STATE, session.familyId)
        return null
    }

    fun setHouseholdTier(tier: BigDecimal) {
        if (!isHead.value) return
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
familyStateDao.upsert(
                    current.copy(
                        householdTier = tier,
                        budget = tier.add(current.memberTier),
                    )
                )
                dirtyMarker.markUpsert(SyncTables.FAMILY_STATE, current.familyId)
            }
    }

    fun setSplitRule(rule: CommonSplitRule) {
        if (!isHead.value) return
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
            familyStateDao.upsert(current.copy(commonSplitRule = rule.name))
            dirtyMarker.markUpsert(SyncTables.FAMILY_STATE, current.familyId)
        }
    }

    fun setHouseholdDetailVisibleToAll(visible: Boolean) {
        if (!isHead.value) return
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            val current = familyStateDao.getByFamilyId(session.familyId) ?: return@launch
            familyStateDao.upsert(current.copy(householdDetailVisibleToAll = visible))
            dirtyMarker.markUpsert(SyncTables.FAMILY_STATE, current.familyId)
        }
    }
}
