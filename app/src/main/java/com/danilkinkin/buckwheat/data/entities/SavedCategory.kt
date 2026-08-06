package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class SavedCategory(
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "emoji", defaultValue = "")
    val emoji: String = "",
) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}
