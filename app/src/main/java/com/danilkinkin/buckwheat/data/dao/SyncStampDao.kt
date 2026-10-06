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
}