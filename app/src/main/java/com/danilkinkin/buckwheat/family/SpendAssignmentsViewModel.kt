package com.danilkinkin.buckwheat.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SpendAssignmentDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilyMembersCache
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the head's assignment requests and the member's answers to them.
 *
 * Two roles in one ViewModel because they are one object seen from two sides, and splitting them would
 * mean two observers of the same table that could disagree about what has been resolved.
 */
@HiltViewModel
class SpendAssignmentsViewModel @Inject constructor(
    private val spendAssignmentDao: SpendAssignmentDao,
    private val transactionDao: TransactionDao,
    private val dirtyMarker: SyncDirtyMarker,
    private val sessionStore: FamilySessionStore,
    private val membersCache: FamilyMembersCache,
) : ViewModel() {

    // `session()` and `members()` are plain Flows, not StateFlows, so they have no `.value`. Held as
    // StateFlows here because every action below needs the current member synchronously and re-reading
    // a DataStore-backed flow on each tap would be both slow and racy against an enrolment change
    // happening underneath.
    private val session: StateFlow<FamilySession?> = sessionStore.session()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val roster: StateFlow<List<FamilyMember>> = membersCache.members()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The signed-in member, or null when the device is not enrolled. */
    val me: StateFlow<String?> = session
        .map { it?.memberId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isHead: StateFlow<Boolean> = session
        .map { s -> s?.memberId?.let { id -> roster.value.firstOrNull { it.id == id }?.isOwner } == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Everything, not just what is pending.
     *
     * The head needs the resolved ones to see that an answer landed, and hiding them would make a
     * rejected request look as though it had simply vanished.
     */
    val assignments: StateFlow<List<SpendAssignment>> = spendAssignmentDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Only the head raises requests, and never one aimed at themselves. */
    fun create(
        targetMemberId: String,
        amount: BigDecimal,
        comment: String,
        category: String?,
        date: Date,
        periodId: String,
    ): Boolean {
        val me = session.value?.memberId ?: return false
        if (targetMemberId == me) return false
        if (amount.signum() <= 0) return false

        val assignment = SpendAssignment(
            periodId = periodId,
            targetMemberId = targetMemberId,
            createdByMemberId = me,
            amount = amount,
            category = category,
            comment = comment.trim(),
            date = date,
            familyId = session.value?.familyId,
        )
        viewModelScope.launch {
            spendAssignmentDao.upsert(assignment)
            dirtyMarker.markUpsert(SyncTables.SPEND_ASSIGNMENTS, assignment.id)
        }
        return true
    }

    /**
     * Answers a request, and on acceptance writes the spend it becomes.
     *
     * Both writes go in one `viewModelScope.launch` so a crash between them cannot leave a request
     * marked ACCEPTED with no spend behind it — the one state in which the money has vanished from
     * nobody's budget and appeared in nobody's history.
     */
    fun answer(assignment: SpendAssignment, accept: Boolean) {
        val me = session.value?.memberId ?: return
        viewModelScope.launch {
            val resolved = resolve(assignment, me, accept, Date()) ?: return@launch
            spendAssignmentDao.upsert(resolved)
            dirtyMarker.markUpsert(SyncTables.SPEND_ASSIGNMENTS, resolved.id)

            val spend = materialise(resolved) ?: return@launch
            transactionDao.insert(spend)
            dirtyMarker.markUpsert(SyncTables.TRANSACTIONS, spend.id)
        }
    }

    fun statusOf(assignment: SpendAssignment): SpendAssignmentStatus = assignment.assignmentStatus

    fun canAnswer(assignment: SpendAssignment): Boolean =
        resolutionProblem(assignment, me.value.orEmpty()) == null

    fun displayName(memberId: String): String =
        roster.value.firstOrNull { it.id == memberId }?.displayName ?: memberId
}