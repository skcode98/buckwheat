package com.danilkinkin.buckwheat.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tag_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("categoryId")],
)
data class TagCategory(
    @PrimaryKey val tagName: String,
    val categoryId: Long,
)
