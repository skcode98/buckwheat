package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "pending_mutations",
    primaryKeys = ["table_name", "record_id"],
)
data class PendingMutation(

    @ColumnInfo(name = "table_name")
    val table: String,

    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "queued_at")
    val queuedAt: Long,

    @ColumnInfo(name = "is_delete")
    val isDelete: Boolean = false,
)
