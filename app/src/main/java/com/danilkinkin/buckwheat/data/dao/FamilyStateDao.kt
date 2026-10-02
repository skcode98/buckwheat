package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.danilkinkin.buckwheat.data.entities.FamilyState
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyStateDao {
    @Query("SELECT * FROM family_state WHERE family_id = :familyId")
    fun observe(familyId: String): Flow<FamilyState?>

    @Query("SELECT * FROM family_state WHERE family_id = :familyId")
    suspend fun getByFamilyId(familyId: String): FamilyState?

    @Query("SELECT * FROM family_state")
    suspend fun getAllNow(): List<FamilyState>

    /**
     * Written by hand for the reason every other synced upsert in this codebase is: `@Insert(REPLACE)`
     * deletes then reinserts and `@Upsert` freezes every non-key column, which here would pin
     * `family_id`, `sync_seq`, `updated_at` and `version` and make a pull silently apply nothing.
     *
     * A column dropped from the SET list silently stops syncing. Keep it in step with the entity.
     */
    @Query(
        """
        INSERT INTO `family_state` (
            `family_id`, `budget`, `household_tier`, `start_date`, `finish_date`, `currency`,
            `household_detail_visible_to_all`, `common_split_rule`, `tags_visible_to_self`,
            `family_ai_enabled`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :familyId, :budget, :householdTier, :startDate, :finishDate, :currency,
            :householdDetailVisibleToAll, :commonSplitRule, :tagsVisibleToSelf,
            :familyAiEnabled, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`family_id`) DO UPDATE SET
            `budget` = excluded.`budget`,
            `household_tier` = excluded.`household_tier`,
            `start_date` = excluded.`start_date`,
            `finish_date` = excluded.`finish_date`,
            `currency` = excluded.`currency`,
            `household_detail_visible_to_all` = excluded.`household_detail_visible_to_all`,
            `common_split_rule` = excluded.`common_split_rule`,
            `tags_visible_to_self` = excluded.`tags_visible_to_self`,
            `family_ai_enabled` = excluded.`family_ai_enabled`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertOne(
        familyId: String,
        budget: BigDecimal,
        householdTier: BigDecimal,
        startDate: Long,
        finishDate: Long,
        currency: String,
        householdDetailVisibleToAll: Boolean,
        commonSplitRule: String,
        tagsVisibleToSelf: Boolean,
        familyAiEnabled: Boolean,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    suspend fun upsert(state: FamilyState) {
        upsertOne(
            familyId = state.familyId,
            budget = state.budget,
            householdTier = state.householdTier,
            startDate = state.startDate,
            finishDate = state.finishDate,
            currency = state.currency,
            householdDetailVisibleToAll = state.householdDetailVisibleToAll,
            commonSplitRule = state.commonSplitRule,
            tagsVisibleToSelf = state.tagsVisibleToSelf,
            familyAiEnabled = state.familyAiEnabled,
            syncSeq = state.syncSeq,
            updatedAt = state.updatedAt,
            deletedAt = state.deletedAt,
            version = state.version,
        )
    }

    @Insert
    suspend fun insertAll(states: List<FamilyState>)

    @Update
    suspend fun update(state: FamilyState)

    @Query("DELETE FROM family_state WHERE family_id = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM family_state")
    suspend fun deleteAll()
}