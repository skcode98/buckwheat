package com.danilkinkin.buckwheat.data.entities

import org.json.JSONObject
import java.math.BigDecimal
import java.util.Date
import java.util.UUID

data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetAmount: BigDecimal,
    val savedAmount: BigDecimal = BigDecimal.ZERO,
    val deadline: Date? = null,
    val createdAt: Date = Date(),
    val completed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("targetAmount", targetAmount.toString())
        put("savedAmount", savedAmount.toString())
        put("deadline", deadline?.time)
        put("createdAt", createdAt.time)
        put("completed", completed)
    }

    fun progress(): Float = if (targetAmount > BigDecimal.ZERO) {
        (savedAmount / targetAmount).toFloat().coerceIn(0f, 1f)
    } else 0f

    companion object {
        fun fromJson(json: JSONObject): Goal = Goal(
            id = json.getString("id"),
            name = json.getString("name"),
            targetAmount = BigDecimal(json.getString("targetAmount")),
            savedAmount = if (json.has("savedAmount")) BigDecimal(json.optString("savedAmount", "0")) else BigDecimal.ZERO,
            deadline = if (json.has("deadline") && !json.isNull("deadline")) Date(json.getLong("deadline")) else null,
            createdAt = if (json.has("createdAt")) Date(json.getLong("createdAt")) else Date(),
            completed = json.optBoolean("completed", false),
        )
    }
}
