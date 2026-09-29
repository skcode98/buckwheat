package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Date

internal fun String?.toBigDecimalPayload(fallback: String = "0"): BigDecimal =
    BigDecimal(if (this.isNullOrBlank()) fallback else this)

internal fun String.readType(): TransactionType =
    runCatching { TransactionType.valueOf(this) }.getOrDefault(TransactionType.SPENT)

internal fun Transaction.businessPayload(): JSONObject = JSONObject()
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("date", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)

internal fun JSONObject.readTransaction(id: String): Transaction = Transaction(
    id = id,
    type = optString("type").readType(),
    value = optString("value", "0").toBigDecimalPayload(),
    date = Date(optLong("date")),
    comment = optString("comment", ""),
    category = optString("category", null),
)

internal fun ArchivedTransaction.businessPayload(): JSONObject = JSONObject()
    .put("periodId", periodId)
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("date", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)

internal fun JSONObject.readArchivedTransaction(id: String): ArchivedTransaction = ArchivedTransaction(
    id = id,
    periodId = optString("periodId"),
    type = optString("type").readType(),
    value = optString("value", "0").toBigDecimalPayload(),
    date = Date(optLong("date")),
    comment = optString("comment", ""),
    category = optString("category", null),
)

internal fun BudgetPeriod.businessPayload(): JSONObject = JSONObject()
    .put("budget", budget.toPlainString())
    .put("startDate", startDate.time)
    .put("finishDate", finishDate.time)
    .put("actualFinishDate", actualFinishDate?.time ?: JSONObject.NULL)
    .put("currencyCode", currencyCode)
    .put("totalSpent", totalSpent.toPlainString())
    .put("isImported", isImported)

internal fun JSONObject.readBudgetPeriod(id: String): BudgetPeriod = BudgetPeriod(
    id = id,
    budget = optString("budget", "0").toBigDecimalPayload(),
    startDate = Date(optLong("startDate")),
    finishDate = Date(optLong("finishDate")),
    actualFinishDate = if (isNull("actualFinishDate")) null else Date(optLong("actualFinishDate")),
    currencyCode = optString("currencyCode", "USD"),
    totalSpent = optString("totalSpent", "0").toBigDecimalPayload(),
    isImported = optBoolean("isImported", false),
)

internal fun SavedCategory.businessPayload(): JSONObject = JSONObject()
    .put("name", name)
    .put("emoji", emoji)

internal fun JSONObject.readSavedCategory(id: String): SavedCategory = SavedCategory(
    id = id,
    name = optString("name"),
    emoji = optString("emoji", ""),
)

internal fun SavedTag.businessPayload(): JSONObject = JSONObject().put("name", name)

internal fun JSONObject.readSavedTag(id: String): SavedTag = SavedTag(
    id = id,
    name = optString("name"),
)

internal fun RecurringTemplate.businessPayload(): JSONObject = JSONObject()
    .put("amount", amount.toPlainString())
    .put("comment", comment)
    .put("dayOfMonth", dayOfMonth)
    .put("enabled", enabled)

internal fun JSONObject.readRecurringTemplate(id: String): RecurringTemplate = RecurringTemplate(
    id = id,
    amount = optString("amount", "0").toBigDecimalPayload(),
    comment = optString("comment", ""),
    dayOfMonth = optInt("dayOfMonth", 1),
    enabled = optBoolean("enabled", true),
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
    name = optString("name"),
    targetAmount = optString("targetAmount", "0").toBigDecimalPayload(),
    currentAmount = optString("currentAmount", "0").toBigDecimalPayload(),
    deadline = if (isNull("deadline")) null else Date(optLong("deadline")),
    createdAt = Date(optLong("createdAt")),
    completed = optBoolean("completed", false),
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
