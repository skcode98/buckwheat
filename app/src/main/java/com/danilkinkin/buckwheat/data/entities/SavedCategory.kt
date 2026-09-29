package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_categories",
    indices = [Index(value = ["name"], unique = true), Index("family_id")],
)
data class SavedCategory(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "emoji", defaultValue = "")
    val emoji: String = "",

    @ColumnInfo(name = "family_id")
    val familyId: String? = null,

    @ColumnInfo(name = "sync_seq", defaultValue = "0")
    val syncSeq: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "version", defaultValue = "1")
    val version: Int = 1,
)
