package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.CommonSplitRule
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import com.danilkinkin.buckwheat.data.entities.SpendBucket
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Date

/**
 * A payload the server sent cannot be turned into a row. Typed so the engine reports a bad payload
 * instead of an anonymous sync failure. Extends [JSONException] because it stands in for the parse
 * failures [JSONObject] raises for the very same broken input.
 */
internal class SyncPayloadException(message: String) : JSONException(message)

internal fun String?.toBigDecimalPayload(fallback: String = "0"): BigDecimal {
    val raw = if (isNullOrBlank()) fallback else this
    return try {
        BigDecimal(raw)
    } catch (failure: NumberFormatException) {
        throw SyncPayloadException("\"$raw\" is not a number")
    }
}

// An unknown type is a renamed or corrupt value, never a spend: guessing silently turns an income
// marker into a spend on every device that reads the payload back.
internal fun String.readType(): TransactionType =
    TransactionType.entries.firstOrNull { it.name == this }
        ?: throw SyncPayloadException("unknown transaction type \"$this\"")

internal fun JSONObject.requireString(key: String): String {
    if (!has(key) || isNull(key)) throw JSONException("payload key $key is missing")
    return getString(key)
}

internal fun JSONObject.requireLong(key: String): Long {
    if (!has(key) || isNull(key)) throw JSONException("payload key $key is missing")
    return getLong(key)
}

internal fun JSONObject.requireBoolean(key: String): Boolean {
    if (!has(key) || isNull(key)) throw JSONException("payload key $key is missing")
    return getBoolean(key)
}

/**
 * Android's [JSONObject.optString] with a fallback is `JSON.toString(opt(name))`, so a JSON null
 * comes back as the four character string "null" instead of the fallback. The desktop org.json used
 * by the JVM tests returns the fallback, which is why this only showed up on device. Never pass a
 * fallback for a nullable column, read it explicitly instead.
 */
internal fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else getString(key)

/**
 * `bucket` is part of the business payload rather than sync metadata, so it has to travel. Without it
 * a household rent pulled from another device lands as `MEMBER`, which moves the money out of the
 * household tier and into a member's remaining budget, and does so silently.
 *
 * Read with a fallback rather than `optNullableString`, because the column is never null and an older
 * payload that lacks the key has to keep loading as the member spend it always was.
 */
internal fun Transaction.businessPayload(): JSONObject = JSONObject()
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("spentAt", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)
    .put("bucket", bucket)
    .put("assignmentId", assignmentId ?: JSONObject.NULL)
    .put("assignedByMemberId", assignedByMemberId ?: JSONObject.NULL)

internal fun JSONObject.readTransaction(id: String): Transaction = Transaction(
    id = id,
    type = requireString("type").readType(),
    value = requireString("value").toBigDecimalPayload(),
    date = Date(requireLong("spentAt")),
    comment = optString("comment", ""),
    category = optNullableString("category"),
    bucket = optString("bucket", SpendBucket.MEMBER.name),
    assignmentId = optNullableString("assignmentId"),
    assignedByMemberId = optNullableString("assignedByMemberId"),
)

internal fun ArchivedTransaction.businessPayload(): JSONObject = JSONObject()
    .put("periodId", periodId)
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("spentAt", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)
    .put("bucket", bucket)
    .put("assignmentId", assignmentId ?: JSONObject.NULL)
    .put("assignedByMemberId", assignedByMemberId ?: JSONObject.NULL)

internal fun JSONObject.readArchivedTransaction(id: String): ArchivedTransaction = ArchivedTransaction(
    id = id,
    periodId = requireString("periodId"),
    type = requireString("type").readType(),
    value = requireString("value").toBigDecimalPayload(),
    date = Date(requireLong("spentAt")),
    comment = optString("comment", ""),
    category = optNullableString("category"),
    bucket = optString("bucket", SpendBucket.MEMBER.name),
    assignmentId = optNullableString("assignmentId"),
    assignedByMemberId = optNullableString("assignedByMemberId"),
)

/**
 * The pool and the four household policies travel as one payload because they are edited as one thing.
 * `familyId` is not in it: it is the record's id, since it is the table's primary key, and sending it
 * twice would give the two a chance to disagree.
 */
internal fun FamilyState.businessPayload(): JSONObject = JSONObject()
    .put("budget", budget.toPlainString())
    .put("householdTier", householdTier.toPlainString())
    .put("startDate", startDate)
    .put("finishDate", finishDate)
    .put("currency", currency)
    .put("householdDetailVisibleToAll", householdDetailVisibleToAll)
    .put("commonSplitRule", commonSplitRule)
    .put("tagsVisibleToSelf", tagsVisibleToSelf)
    .put("familyAiEnabled", familyAiEnabled)

internal fun JSONObject.readFamilyState(familyId: String): FamilyState = FamilyState(
    familyId = familyId,
    budget = requireString("budget").toBigDecimalPayload(),
    householdTier = requireString("householdTier").toBigDecimalPayload(),
    startDate = requireLong("startDate"),
    finishDate = requireLong("finishDate"),
    currency = optString("currency", ""),
    householdDetailVisibleToAll = optBoolean("householdDetailVisibleToAll", false),
    commonSplitRule = optString("commonSplitRule", CommonSplitRule.EQUAL.name),
    tagsVisibleToSelf = optBoolean("tagsVisibleToSelf", true),
    familyAiEnabled = optBoolean("familyAiEnabled", true),
)

internal fun PeriodLimit.businessPayload(): JSONObject = JSONObject()
    .put("periodId", periodId)
    .put("memberId", memberId)
    .put("limitValue", limitValue.toPlainString())

internal fun JSONObject.readPeriodLimit(id: String): PeriodLimit = PeriodLimit(
    id = id,
    periodId = requireString("periodId"),
    memberId = requireString("memberId"),
    limitValue = requireString("limitValue").toBigDecimalPayload(),
)

internal fun SpendAssignment.businessPayload(): JSONObject = JSONObject()
    .put("periodId", periodId)
    .put("targetMemberId", targetMemberId)
    .put("createdByMemberId", createdByMemberId)
    .put("amount", amount.toPlainString())
    .put("category", category ?: JSONObject.NULL)
    .put("comment", comment)
    .put("date", date.time)
    .put("status", status)
    .put("resolvedAt", resolvedAt?.time ?: JSONObject.NULL)

internal fun JSONObject.readSpendAssignment(id: String): SpendAssignment = SpendAssignment(
    id = id,
    periodId = requireString("periodId"),
    targetMemberId = requireString("targetMemberId"),
    createdByMemberId = requireString("createdByMemberId"),
    amount = requireString("amount").toBigDecimalPayload(),
    category = optNullableString("category"),
    comment = optString("comment", ""),
    date = Date(requireLong("date")),
    status = optString("status", SpendAssignmentStatus.PENDING.name),
    resolvedAt = if (isNull("resolvedAt")) null else Date(requireLong("resolvedAt")),
)

internal fun FamilyState.withSyncMeta(record: LocalRecord): FamilyState = copy(
    familyId = record.familyId ?: familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun PeriodLimit.withSyncMeta(record: LocalRecord): PeriodLimit = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun SpendAssignment.withSyncMeta(record: LocalRecord): SpendAssignment = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun BudgetPeriod.businessPayload(): JSONObject = JSONObject()
    .put("budget", budget.toPlainString())
    .put("startDate", startDate.time)
    .put("finishDate", finishDate.time)
    .put("actualFinishDate", actualFinishDate?.time ?: JSONObject.NULL)
    .put("currency", currencyCode)
    .put("totalSpent", totalSpent.toPlainString())
    .put("isImported", isImported)

internal fun JSONObject.readBudgetPeriod(id: String): BudgetPeriod = BudgetPeriod(
    id = id,
    budget = requireString("budget").toBigDecimalPayload(),
    startDate = Date(requireLong("startDate")),
    finishDate = Date(requireLong("finishDate")),
    actualFinishDate = if (isNull("actualFinishDate")) null else Date(optLong("actualFinishDate")),
    currencyCode = requireString("currency"),
    totalSpent = requireString("totalSpent").toBigDecimalPayload(),
    isImported = requireBoolean("isImported"),
)

internal fun SavedCategory.businessPayload(): JSONObject = JSONObject()
    .put("name", name)
    .put("emoji", emoji)

internal fun JSONObject.readSavedCategory(id: String): SavedCategory = SavedCategory(
    id = id,
    name = requireString("name"),
    emoji = optString("emoji", ""),
)

internal fun SavedTag.businessPayload(): JSONObject = JSONObject().put("name", name)

internal fun JSONObject.readSavedTag(id: String): SavedTag = SavedTag(
    id = id,
    name = requireString("name"),
)

internal fun RecurringTemplate.businessPayload(): JSONObject = JSONObject()
    .put("amount", amount.toPlainString())
    .put("comment", comment)
    .put("dayOfMonth", dayOfMonth)
    .put("enabled", enabled)

internal fun JSONObject.readRecurringTemplate(id: String): RecurringTemplate = RecurringTemplate(
    id = id,
    amount = requireString("amount").toBigDecimalPayload(),
    comment = optString("comment", ""),
    dayOfMonth = optInt("dayOfMonth", 1),
    enabled = requireBoolean("enabled"),
)

internal fun SavingsGoal.businessPayload(): JSONObject = JSONObject()
    .put("name", name)
    .put("targetAmount", targetAmount.toPlainString())
    .put("currentAmount", currentAmount.toPlainString())
    .put("deadline", deadline?.time ?: JSONObject.NULL)
    .put("createdAt", createdAt.time)
    .put("completed", completed)

internal fun JSONObject.readSavingsGoal(id: String): SavingsGoal = SavingsGoal(
    id = id,
    name = requireString("name"),
    targetAmount = requireString("targetAmount").toBigDecimalPayload(),
    currentAmount = requireString("currentAmount").toBigDecimalPayload(),
    deadline = if (isNull("deadline")) null else Date(optLong("deadline")),
    createdAt = Date(requireLong("createdAt")),
    completed = requireBoolean("completed"),
)

internal fun Transaction.withSyncMeta(record: LocalRecord): Transaction = copy(
    memberId = record.memberId,
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun ArchivedTransaction.withSyncMeta(record: LocalRecord): ArchivedTransaction = copy(
    memberId = record.memberId,
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun BudgetPeriod.withSyncMeta(record: LocalRecord): BudgetPeriod = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun SavedCategory.withSyncMeta(record: LocalRecord): SavedCategory = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun SavedTag.withSyncMeta(record: LocalRecord): SavedTag = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun RecurringTemplate.withSyncMeta(record: LocalRecord): RecurringTemplate = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)

internal fun SavingsGoal.withSyncMeta(record: LocalRecord): SavingsGoal = copy(
    familyId = record.familyId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)
