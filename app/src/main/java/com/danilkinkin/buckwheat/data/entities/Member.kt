package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "members",
    indices = [Index("family_id")],
)
data class Member(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "family_id")
    val familyId: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "is_owner", defaultValue = "0")
    val isOwner: Boolean = false,

    @ColumnInfo(name = "joined_at")
    val joinedAt: Date,
)
