package com.danilkinkin.buckwheat.data.categories

import androidx.annotation.StringRes
import com.danilkinkin.buckwheat.R

// Fixed, predefined spend categories. Records are assigned to exactly one of these, either
// offline via keyword matching (SpendCategorizer.offlineClassify) or by the AI model. The
// enum name is the value persisted in the transactions.category column and the key sent to
// the AI — users never create or edit categories themselves.
enum class SpendCategory(
    @StringRes val labelRes: Int,
    val emoji: String,
    val keywords: List<String>,
) {
    TRAVEL(
        R.string.category_travel,
        "✈️",
        listOf(
            "flight", "airfare", "hotel", "resort", "trip", "tour", "vacation",
            "holiday", "travel", "luggage", "visa",
        ),
    ),
    HEALTH(
        R.string.category_health,
        "💊",
        listOf(
            "doctor", "hospital", "clinic", "dentist", "medicine", "pharmacy",
            "gym", "fitness", "vitamin", "consultation", "lab",
        ),
    ),
    ENTERTAINMENT(
        R.string.category_entertainment,
        "🎬",
        listOf(
            "movie", "cinema", "film", "game", "concert", "party", "music",
            "netflix", "spotify", "hobby", "entertainment",
        ),
    ),
    BILLS(
        R.string.category_bills,
        "🧾",
        listOf(
            "rent", "electricity", "water", "internet", "wifi", "recharge",
            "subscription", "phone bill", "gas", "emi", "insurance", "tax", "bill",
        ),
    ),
    TRANSPORT(
        R.string.category_transport,
        "🚕",
        listOf(
            "bus", "taxi", "uber", "ola", "train", "metro", "petrol", "diesel",
            "fuel", "auto", "cab", "parking", "toll", "rickshaw", "commute",
            "transport", "fare",
        ),
    ),
    SHOPPING(
        R.string.category_shopping,
        "🛍️",
        listOf(
            "shopping", "clothes", "dress", "shoes", "amazon", "flipkart",
            "store", "purchase", "gift", "electronics", "laptop", "mobile",
            "watch", "bag", "cosmetic", "jeans", "t-shirt",
        ),
    ),
    FOOD(
        R.string.category_food,
        "🍔",
        listOf(
            "food", "lunch", "dinner", "breakfast", "snack", "tea", "coffee",
            "pizza", "restaurant", "cafe", "groceries", "grocery", "milk",
            "bread", "rice", "fruit", "vegetable", "biryani", "curry", "burger",
            "cake", "juice", "sweets", "eggs", "chicken", "meal", "foodcourt",
        ),
    ),
    OTHER(R.string.category_other, "🗂️", emptyList()),
    ;

    companion object {
        // Fallback shown for a custom category that has no emoji saved (e.g. one that only
        // exists on transactions after its saved entry was deleted).
        const val DEFAULT_EMOJI = "🏷️"

        // Reads the persisted / AI-provided value. Tolerant of casing so a model that
        // returns "food" or "Food" still maps to the enum.
        fun fromStored(value: String?): SpendCategory? =
            value?.trim()?.let { stored ->
                entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
            }

        // Emoji to display for a persisted category value: the built-in emoji for predefined
        // categories, the user-picked emoji for a saved custom category, or the generic
        // fallback when a custom category has none.
        fun emojiFor(stored: String?, customEmoji: String?): String =
            fromStored(stored)?.emoji ?: customEmoji?.takeIf { it.isNotBlank() } ?: DEFAULT_EMOJI
    }
}
