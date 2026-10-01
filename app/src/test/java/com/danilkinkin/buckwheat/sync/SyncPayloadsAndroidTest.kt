package com.danilkinkin.buckwheat.sync

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The JVM tests run against the desktop org.json, which returns the fallback from
 * `optString(name, fallback)`. Android's org.json implements it as `JSON.toString(opt(name))`, so a
 * JSON null comes back as the four character string "null". That difference is invisible in
 * `SyncPayloadsTest`, so the nullable payload columns are pinned here against the real Android
 * implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncPayloadsAndroidTest {

    @Test
    fun aJsonNullCategoryDecodesToAKotlinNull() {
        val json = JSONObject()
            .put("type", "SPENT")
            .put("value", "12.50")
            .put("spentAt", 1L)
            .put("comment", "")
            .put("category", JSONObject.NULL)

        assertNull(json.readTransaction("t-1").category)
    }

    @Test
    fun anAbsentCategoryDecodesToAKotlinNull() {
        val json = JSONObject()
            .put("type", "SPENT")
            .put("value", "12.50")
            .put("spentAt", 1L)
            .put("comment", "")

        assertNull(json.readTransaction("t-1").category)
    }

    @Test
    fun aJsonNullCategoryOnAnArchivedTransactionDecodesToAKotlinNull() {
        val json = JSONObject()
            .put("type", "SPENT")
            .put("value", "12.50")
            .put("spentAt", 1L)
            .put("comment", "")
            .put("periodId", "p-1")
            .put("category", JSONObject.NULL)

        assertNull(json.readArchivedTransaction("a-1").category)
    }

    @Test
    fun anAbsentCategoryOnAnArchivedTransactionDecodesToAKotlinNull() {
        val json = JSONObject()
            .put("type", "SPENT")
            .put("value", "12.50")
            .put("spentAt", 1L)
            .put("comment", "")
            .put("periodId", "p-1")

        assertNull(json.readArchivedTransaction("a-1").category)
    }

    @Test
    fun aPresentCategoryIsStillReadBack() {
        val json = JSONObject()
            .put("type", "SPENT")
            .put("value", "12.50")
            .put("spentAt", 1L)
            .put("comment", "")
            .put("category", "food")

        assertTrue(json.readTransaction("t-1").category == "food")
    }

    @Test
    fun anExplicitJsonNullDeadlineStaysNull() {
        val json = JSONObject()
            .put("name", "holiday")
            .put("targetAmount", "100")
            .put("currentAmount", "0")
            .put("deadline", JSONObject.NULL)
            .put("createdAt", 1L)
            .put("completed", false)

        assertNull(json.readSavingsGoal("g-1").deadline)
    }
}