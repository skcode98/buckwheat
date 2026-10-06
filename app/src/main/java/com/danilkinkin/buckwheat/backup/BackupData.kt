package com.danilkinkin.buckwheat.backup

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.SpendAssignmentStatus
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.SpendBucket
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.newSyncId
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Date

const val BACKUP_VERSION = 1

const val BACKUP_APP_TAG = "buckwheat"
const val BACKUP_APP_TAG_KEY = "app"
const val BACKUP_VERSION_KEY = "version"
const val BACKUP_EXPORTED_AT_KEY = "exportedAt"

// Typed value for one DataStore preference entry, so a restore can recreate the exact
// Preferences.Key<*> type (a plain JSON value would blur Int vs Long vs String).
sealed class BackupValue {
    data class Bool(val value: Boolean) : BackupValue()
    data class IntValue(val value: Int) : BackupValue()
    data class LongValue(val value: Long) : BackupValue()
    data class FloatValue(val value: Float) : BackupValue()
    data class Str(val value: String) : BackupValue()
    data class StrSet(val value: Set<String>) : BackupValue()

    fun toJson(): JSONObject = when (this) {
        is Bool -> JSONObject().put("type", "Boolean").put("value", value)
        is IntValue -> JSONObject().put("type", "Int").put("value", value)
        is LongValue -> JSONObject().put("type", "Long").put("value", value)
        is FloatValue -> JSONObject().put("type", "Float").put("value", value.toDouble())
        is Str -> JSONObject().put("type", "String").put("value", value)
        is StrSet -> JSONObject().put("type", "StringSet").put("value", JSONArray(value.toList()))
    }

    companion object {
        fun fromJson(obj: JSONObject): BackupValue? {
            return when (obj.optString("type")) {
                "Boolean" -> BackupValue.Bool(obj.optBoolean("value"))
                "Int" -> BackupValue.IntValue(obj.optInt("value"))
                "Long" -> BackupValue.LongValue(obj.optLong("value"))
                "Float" -> BackupValue.FloatValue(obj.optDouble("value").toFloat())
                "String" -> BackupValue.Str(obj.optString("value"))
                "StringSet" -> {
                    val array = obj.optJSONArray("value")
                    val values = if (array == null) {
                        emptyList()
                    } else {
                        buildList {
                            for (i in 0 until array.length()) {
                                add(array.optString(i))
                            }
                        }
                    }
                    BackupValue.StrSet(values.toSet())
                }
                else -> null
            }
        }
    }
}

/**
 * The family tables carry no defaults, so a backup taken before this feature existed still parses:
 * each list is absent from the JSON and becomes empty.
 *
 * None of them writes `family_id`, `member_id`, `sync_seq`, `updated_at` or `version`, so a restored
 * family budget comes back as local data that is not attached to any family. That is deliberate and is
 * the same rule the transactions follow: a restore must never be able to enrol a device.
 */
data class BackupData(
    val version: Int,
    val exportedAt: Long,
    val transactions: List<Transaction>,
    val budgetPeriods: List<BudgetPeriod>,
    val archivedTransactions: List<ArchivedTransaction>,
    val savedTags: List<SavedTag>,
    val savedCategories: List<SavedCategory>,
    val recurringTemplates: List<RecurringTemplate>,
    val savingsGoals: List<SavingsGoal>,
    val periodLimits: List<PeriodLimit> = emptyList(),
    val spendAssignments: List<SpendAssignment> = emptyList(),
    val budgetPreferences: Map<String, BackupValue>,
    val settingsPreferences: Map<String, BackupValue>,
)

// ---------- Serialization ----------

fun BackupData.toJsonString(): String {
    val root = JSONObject()
        .put(BACKUP_APP_TAG_KEY, BACKUP_APP_TAG)
        .put(BACKUP_VERSION_KEY, version)
        .put(BACKUP_EXPORTED_AT_KEY, exportedAt)
        .put("transactions", JSONArray(transactions.map { it.toJson() }))
        .put("budgetPeriods", JSONArray(budgetPeriods.map { it.toJson() }))
        .put("archivedTransactions", JSONArray(archivedTransactions.map { it.toJson() }))
        .put("savedTags", JSONArray(savedTags.map { it.toJson() }))
        .put("savedCategories", JSONArray(savedCategories.map { it.toJson() }))
        .put("recurringTemplates", JSONArray(recurringTemplates.map { it.toJson() }))
        .put("savingsGoals", JSONArray(savingsGoals.map { it.toJson() }))
        .put("periodLimits", JSONArray(periodLimits.map { it.toJson() }))
        .put("spendAssignments", JSONArray(spendAssignments.map { it.toJson() }))
        .put("budgetPreferences", preferencesToJson(budgetPreferences))
        .put("settingsPreferences", preferencesToJson(settingsPreferences))
    return root.toString()
}

// ---------- Deserialization ----------

fun parseBackupData(json: String): BackupData? {
    return try {
        val root = JSONObject(json)
        if (root.optString(BACKUP_APP_TAG_KEY) != BACKUP_APP_TAG) return null
        val version = root.optInt(BACKUP_VERSION_KEY)
        if (version != BACKUP_VERSION) return null

        BackupData(
            version = version,
            exportedAt = root.optLong(BACKUP_EXPORTED_AT_KEY),
            transactions = root.optJSONArray("transactions")?.toTransactionList() ?: emptyList(),
            budgetPeriods = root.optJSONArray("budgetPeriods")?.toBudgetPeriodList() ?: emptyList(),
            archivedTransactions = root.optJSONArray("archivedTransactions")
                ?.toArchivedTransactionList() ?: emptyList(),
            savedTags = root.optJSONArray("savedTags")?.toSavedTagList() ?: emptyList(),
            savedCategories = root.optJSONArray("savedCategories")
                ?.toSavedCategoryList() ?: emptyList(),
            recurringTemplates = root.optJSONArray("recurringTemplates")
                ?.toRecurringTemplateList() ?: emptyList(),
            savingsGoals = root.optJSONArray("savingsGoals")?.toSavingsGoalList() ?: emptyList(),
            periodLimits = root.optJSONArray("periodLimits")?.toPeriodLimitList() ?: emptyList(),
            spendAssignments = root.optJSONArray("spendAssignments")
                ?.toSpendAssignmentList() ?: emptyList(),
            budgetPreferences = preferencesFromJson(
                root.optJSONObject("budgetPreferences") ?: JSONObject()
            ),
            settingsPreferences = preferencesFromJson(
                root.optJSONObject("settingsPreferences") ?: JSONObject()
            ),
        )
    } catch (_: Exception) {
        null
    }
}

// ---------- Entity codecs ----------

/**
 * The sync columns are deliberately absent, so a restored row comes back as pre-enrolment local
 * data and is never silently attached to a family. `bucket` is the exception: it is not sync
 * metadata, it changes what the money *is*. Restoring a household rent as a member spend would move
 * it out of the household tier and into an anonymous bucket, which is a different ledger rather than
 * a lossy one.
 *
 * The assignment provenance is not written, because it points at a `spend_assignments` row that is
 * itself not backed up. A dangling link would be worse than none.
 */
private fun Transaction.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("date", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)
    .put("bucket", bucket)

private fun JSONObject.toTransaction(): Transaction = Transaction(
    id = optString("id").ifBlank { newSyncId() },
    type = TransactionType.valueOf(optString("type", TransactionType.SPENT.name)),
    value = BigDecimal(optString("value", "0")),
    date = Date(optLong("date")),
    comment = optString("comment"),
    category = if (isNull("category")) null else optString("category", null),
    bucket = optString("bucket", SpendBucket.MEMBER.name),
)

private fun BudgetPeriod.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("budget", budget.toPlainString())
    .put("startDate", startDate.time)
    .put("finishDate", finishDate.time)
    .put("actualFinishDate", actualFinishDate?.time ?: JSONObject.NULL)
    .put("currencyCode", currencyCode)
    .put("totalSpent", totalSpent.toPlainString())
    .put("isImported", isImported)

private fun JSONObject.toBudgetPeriod(): BudgetPeriod = BudgetPeriod(
    id = optString("id").ifBlank { newSyncId() },
    budget = BigDecimal(optString("budget", "0")),
    startDate = Date(optLong("startDate")),
    finishDate = Date(optLong("finishDate")),
    actualFinishDate = if (isNull("actualFinishDate")) null else Date(optLong("actualFinishDate")),
    currencyCode = optString("currencyCode"),
    totalSpent = BigDecimal(optString("totalSpent", "0")),
    isImported = optBoolean("isImported"),
)

private fun ArchivedTransaction.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("periodId", periodId)
    .put("type", type.name)
    .put("value", value.toPlainString())
    .put("date", date.time)
    .put("comment", comment)
    .put("category", category ?: JSONObject.NULL)
    .put("bucket", bucket)

private fun JSONObject.toArchivedTransaction(): ArchivedTransaction = ArchivedTransaction(
    id = optString("id").ifBlank { newSyncId() },
    periodId = optString("periodId"),
    type = TransactionType.valueOf(optString("type", TransactionType.SPENT.name)),
    value = BigDecimal(optString("value", "0")),
    date = Date(optLong("date")),
    comment = optString("comment"),
    category = if (isNull("category")) null else optString("category", null),
    bucket = optString("bucket", SpendBucket.MEMBER.name),
)

/**
 * No `family_id` on any of these, on purpose. A restored allocation is a number the person typed, not
 * a claim about a family they may no longer be in, and re-attaching it to one silently would put
 * somebody else's budget back on their screen.
 */
private fun PeriodLimit.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("periodId", periodId)
    .put("memberId", memberId)
    .put("limitValue", limitValue.toPlainString())

private fun JSONObject.toPeriodLimit(): PeriodLimit = PeriodLimit(
    id = optString("id").ifBlank { newSyncId() },
    periodId = optString("periodId"),
    memberId = optString("memberId"),
    limitValue = BigDecimal(optString("limitValue", "0")),
)

private fun SpendAssignment.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("periodId", periodId)
    .put("targetMemberId", targetMemberId)
    .put("createdByMemberId", createdByMemberId)
    .put("amount", amount.toPlainString())
    .put("category", category ?: JSONObject.NULL)
    .put("comment", comment)
    .put("date", date.time)
    .put("status", status)
    .put("resolvedAt", resolvedAt?.time ?: JSONObject.NULL)

private fun JSONObject.toSpendAssignment(): SpendAssignment = SpendAssignment(
    id = optString("id").ifBlank { newSyncId() },
    periodId = optString("periodId"),
    targetMemberId = optString("targetMemberId"),
    createdByMemberId = optString("createdByMemberId"),
    amount = BigDecimal(optString("amount", "0")),
    category = if (isNull("category")) null else optString("category", null),
    comment = optString("comment"),
    date = Date(optLong("date")),
    status = optString("status", SpendAssignmentStatus.PENDING.name),
    resolvedAt = if (isNull("resolvedAt")) null else Date(optLong("resolvedAt")),
)

private fun SavedTag.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)

private fun JSONObject.toSavedTag(): SavedTag = SavedTag(
    id = optString("id").ifBlank { newSyncId() },
    name = optString("name"),
)

private fun SavedCategory.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("emoji", emoji)

private fun JSONObject.toSavedCategory(): SavedCategory = SavedCategory(
    id = optString("id").ifBlank { newSyncId() },
    name = optString("name"),
    emoji = optString("emoji"),
)

private fun RecurringTemplate.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("amount", amount.toPlainString())
    .put("comment", comment)
    .put("dayOfMonth", dayOfMonth)
    .put("enabled", enabled)

private fun JSONObject.toRecurringTemplate(): RecurringTemplate = RecurringTemplate(
    id = optString("id").ifBlank { newSyncId() },
    amount = BigDecimal(optString("amount", "0")),
    comment = optString("comment"),
    dayOfMonth = optInt("dayOfMonth"),
    enabled = optBoolean("enabled"),
)

private fun SavingsGoal.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("targetAmount", targetAmount.toPlainString())
    .put("currentAmount", currentAmount.toPlainString())
    .put("deadline", deadline?.time ?: JSONObject.NULL)
    .put("createdAt", createdAt.time)
    .put("completed", completed)

private fun JSONObject.toSavingsGoal(): SavingsGoal = SavingsGoal(
    id = optString("id").ifBlank { newSyncId() },
    name = optString("name"),
    targetAmount = BigDecimal(optString("targetAmount", "0")),
    currentAmount = BigDecimal(optString("currentAmount", "0")),
    deadline = if (isNull("deadline")) null else Date(optLong("deadline")),
    createdAt = Date(optLong("createdAt")),
    completed = optBoolean("completed"),
)

// ---------- Preferences codecs ----------

private fun preferencesToJson(prefs: Map<String, BackupValue>): JSONObject {
    val obj = JSONObject()
    prefs.forEach { (name, value) -> obj.put(name, value.toJson()) }
    return obj
}

private fun preferencesFromJson(obj: JSONObject): Map<String, BackupValue> {
    val result = LinkedHashMap<String, BackupValue>()
    obj.keys().forEach { name ->
        obj.optJSONObject(name)?.let { value ->
            BackupValue.fromJson(value)?.let { result[name] = it }
        }
    }
    return result
}

// ---------- Array helpers ----------

private fun JSONArray.toTransactionList(): List<Transaction> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toTransaction()) }
    }
}

private fun JSONArray.toBudgetPeriodList(): List<BudgetPeriod> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toBudgetPeriod()) }
    }
}

private fun JSONArray.toArchivedTransactionList(): List<ArchivedTransaction> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toArchivedTransaction()) }
    }
}

private fun JSONArray.toSavedTagList(): List<SavedTag> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toSavedTag()) }
    }
}

private fun JSONArray.toSavedCategoryList(): List<SavedCategory> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toSavedCategory()) }
    }
}

private fun JSONArray.toRecurringTemplateList(): List<RecurringTemplate> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toRecurringTemplate()) }
    }
}

private fun JSONArray.toSavingsGoalList(): List<SavingsGoal> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toSavingsGoal()) }
    }
}

private fun JSONArray.toPeriodLimitList(): List<PeriodLimit> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toPeriodLimit()) }
    }
}

private fun JSONArray.toSpendAssignmentList(): List<SpendAssignment> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it.toSpendAssignment()) }
    }
}
