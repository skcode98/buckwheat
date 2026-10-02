package com.danilkinkin.buckwheat.family

import com.danilkinkin.buckwheat.data.entities.ResolutionProblem
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.util.Date

/**
 * Whether [memberId] may answer [assignment], and why not when they may not.
 *
 * Three separate refusals rather than one, because they are three different mistakes and the UI has
 * to say which happened. In particular [ResolutionProblem.CREATOR_CANNOT_RESOLVE] is the one that
 * stops the head from manufacturing their own consent: if the head could resolve an assignment they
 * raised, the accept step would be a formality and the feature would mean nothing.
 */
fun resolutionProblem(
    assignment: SpendAssignment,
    memberId: String,
): ResolutionProblem? = when {
    assignment.assignmentStatus.isResolved -> ResolutionProblem.ALREADY_RESOLVED
    assignment.targetMemberId != memberId -> ResolutionProblem.NOT_THE_TARGET
    assignment.createdByMemberId == assignment.targetMemberId -> ResolutionProblem.CREATOR_CANNOT_RESOLVE
    assignment.amount.signum() <= 0 -> ResolutionProblem.AMOUNT_NOT_POSITIVE
    else -> null
}

/**
 * Records an answer, or returns null when the answer is not allowed.
 *
 * Returns the updated row rather than mutating so the caller can decide when to write, which is what
 * makes it usable both online and from the offline queue.
 */
fun resolve(
    assignment: SpendAssignment,
    memberId: String,
    accept: Boolean,
    now: Date,
): SpendAssignment? {
    if (resolutionProblem(assignment, memberId) != null) return null
    return assignment.copy(
        status = if (accept) SpendAssignmentStatus.ACCEPTED.name else SpendAssignmentStatus.REJECTED.name,
        resolvedAt = now,
        updatedAt = now.time,
        version = assignment.version + 1,
    )
}

/**
 * The spend an accepted assignment becomes.
 *
 * Null for a rejected one, which is the whole point of a separate object: rejection creates no row at
 * all, so there is nothing to later "clean up" and no risk of a rejected amount having quietly
 * counted against someone for a day.
 *
 * The row is attributed to the *target*, not the creator, and carries the assignment's id so that
 * applying the same accepted assignment twice on two devices converges on one row instead of two.
 * [com.danilkinkin.buckwheat.data.entities.TransactionType.SPENT] is used unconditionally: a head
 * assigning a daily budget or an income line would be inventing budget, not recording a spend.
 */
fun materialise(assignment: SpendAssignment): Transaction? {
    if (assignment.assignmentStatus != SpendAssignmentStatus.ACCEPTED) return null
    return Transaction(
        id = assignment.id,
        type = TransactionType.SPENT,
        value = assignment.amount,
        date = assignment.date,
        comment = assignment.comment,
        category = assignment.category,
        memberId = assignment.targetMemberId,
        familyId = assignment.familyId,
        assignmentId = assignment.id,
        assignedByMemberId = assignment.createdByMemberId,
        updatedAt = assignment.resolvedAt?.time ?: assignment.updatedAt,
    )
}

/** True when a pulled accepted assignment still needs its row written locally. */
fun needsMaterialising(assignment: SpendAssignment, existingTransactionIds: Set<String>): Boolean =
    assignment.assignmentStatus == SpendAssignmentStatus.ACCEPTED &&
        assignment.id !in existingTransactionIds