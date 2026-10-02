package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Stamps the sync bookkeeping columns directly in SQL so every write path can advance
 * `version` / `updated_at` without first reading the row back into an entity.
 *
 * Nothing in the app used to bump `version` on a local edit, so a record pushed once came
 * back rejected forever: the server compares `incoming.version >= stored.version` and falls
 * back to `incoming.updated_at > stored.updated_at`, and neither ever moved locally.
 */
@Dao
interface SyncStampDao {

    @Query("UPDATE `transactions` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampTransaction(id: String, now: Long): Int

    @Query("UPDATE `archived_transactions` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampArchivedTransaction(id: String, now: Long): Int

    @Query("UPDATE `budget_periods` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampBudgetPeriod(id: String, now: Long): Int

    @Query("UPDATE `saved_categories` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampSavedCategory(id: String, now: Long): Int

    @Query("UPDATE `saved_tags` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampSavedTag(id: String, now: Long): Int

    @Query("UPDATE `recurring_templates` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampRecurringTemplate(id: String, now: Long): Int

    @Query("UPDATE `savings_goals` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampSavingsGoal(id: String, now: Long): Int

    /** Keyed on `family_id`, not `id`: the family *is* this row's primary key. */
    @Query("UPDATE `family_state` SET `version` = `version` + 1, `updated_at` = :now WHERE `family_id` = :familyId")
    suspend fun stampFamilyState(familyId: String, now: Long): Int

    @Query("UPDATE `period_limits` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampPeriodLimit(id: String, now: Long): Int

    @Query("UPDATE `spend_assignments` SET `version` = `version` + 1, `updated_at` = :now WHERE `id` = :id")
    suspend fun stampSpendAssignment(id: String, now: Long): Int

    @Query("UPDATE `transactions` SET `family_id` = NULL, `member_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseTransactions(): Int

    @Query("UPDATE `archived_transactions` SET `family_id` = NULL, `member_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseArchivedTransactions(): Int

    @Query("UPDATE `budget_periods` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseBudgetPeriods(): Int

    @Query("UPDATE `saved_categories` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseSavedCategories(): Int

    @Query("UPDATE `saved_tags` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseSavedTags(): Int

    @Query("UPDATE `recurring_templates` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseRecurringTemplates(): Int

    @Query("UPDATE `savings_goals` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseSavingsGoals(): Int

    /**
     * Leaving a family deletes the pool and the split outright rather than detaching them.
     *
     * Detaching is what the other tables do and would be wrong here. `family_state` is keyed on
     * `family_id`, so there is no row left to keep once the id is nulled, and keeping the amounts
     * would leave a former member's device showing a budget belonging to a family it has left.
     */
    @Query("DELETE FROM `family_state` WHERE `family_id` IS NOT NULL")
    suspend fun releaseFamilyState(): Int

    @Query("UPDATE `period_limits` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releasePeriodLimits(): Int

    @Query("UPDATE `spend_assignments` SET `family_id` = NULL, `sync_seq` = 0, `version` = 1, `updated_at` = 0 WHERE `family_id` IS NOT NULL")
    suspend fun releaseSpendAssignments(): Int
}