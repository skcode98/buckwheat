package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(
    tableName = "family_transactions",
    indices = [Index("member_id"), Index("date"), Index("type")],
)
data class FamilyTransaction(
    @PrimaryKey @ColumnInfo(name = "id") val id: String = newSyncId(),
    @ColumnInfo(name = "type") val type: TransactionType,
    @ColumnInfo(name = "value") val value: BigDecimal,
    @ColumnInfo(name = "date") val date: Date,
    @ColumnInfo(name = "comment", defaultValue = "") val comment: String = "",
    @ColumnInfo(name = "category") val category: String? = null,
    @ColumnInfo(name = "member_id") val memberId: String? = null,
    @ColumnInfo(name = "sync_seq", defaultValue = "0") val syncSeq: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "version", defaultValue = "1") val version: Int = 1,
)
