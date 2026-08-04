package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class SavedTag(
    @ColumnInfo(name = "name")
    val name: String,
) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}
