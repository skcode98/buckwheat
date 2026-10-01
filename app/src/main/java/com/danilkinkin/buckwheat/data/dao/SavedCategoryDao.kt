package com.danilkinkin.buckwheat.data.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Transaction as RoomTransaction
import androidx.room.*
import com.danilkinkin.buckwheat.data.entities.SavedCategory

@Dao
interface SavedCategoryDao {
    @Query("SELECT * FROM saved_categories ORDER BY name ASC")
    fun getAll(): Flow<List<SavedCategory>>

    @Query("SELECT * FROM saved_categories WHERE id = :id")
    suspend fun getById(id: String): SavedCategory?

    @Query("SELECT * FROM saved_categories")
    suspend fun getAllNow(): List<SavedCategory>

    @Query("SELECT * FROM saved_categories WHERE name = :name")
    suspend fun getByName(name: String): SavedCategory?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_categories WHERE name = :name)")
    suspend fun existsByName(name: String): Boolean

    /**
     * A real conflict-resolving upsert, written by hand because neither Room annotation does this job.
     *
     * `@Insert(onConflict = REPLACE)` is WRONG here for the general reason: SQLite's REPLACE deletes
     * the conflicting row and inserts a new one, which cascades wherever this table is a foreign-key
     * parent.
     *
     * `@Upsert` is ALSO WRONG, and worse: on conflict Room updates only the primary key column and
     * leaves every other column at its old value, so `family_id`, `sync_seq`, `updated_at` and
     * `version` were silently frozen. A pull that returned a changed `family_id` or `version`
     * applied nothing, and `enrolAll` stamped nothing.
     *
     * `ON CONFLICT(id) DO UPDATE SET` updates in place and assigns every non-key column from
     * `excluded`. Any column dropped from the SET list silently stops syncing — keep this list in
     * step with the entity.
     */
    @Query(
        """
        INSERT INTO `saved_categories` (
            `id`, `name`, `emoji`, `family_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :name, :emoji, :familyId, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `name` = excluded.`name`,
            `emoji` = excluded.`emoji`,
            `family_id` = excluded.`family_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """
    )
    suspend fun upsertOne(
        id: String,
        name: String,
        emoji: String,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(category: SavedCategory) {
        upsertOne(
            id = category.id,
            name = category.name,
            emoji = category.emoji,
            familyId = category.familyId,
            syncSeq = category.syncSeq,
            updatedAt = category.updatedAt,
            deletedAt = category.deletedAt,
            version = category.version,
        )
    }

    @Insert
    suspend fun insertAll(categories: List<SavedCategory>)

    @Update
    suspend fun update(category: SavedCategory)

    @Query("DELETE FROM saved_categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM saved_categories")
    suspend fun deleteAll()
}
