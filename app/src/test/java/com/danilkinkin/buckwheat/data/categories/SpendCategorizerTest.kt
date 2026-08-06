package com.danilkinkin.buckwheat.data.categories

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

class SpendCategorizerTest {

    @Test
    fun `offlineClassify maps common comments to their category`() {
        assertEquals(SpendCategory.FOOD, offlineClassify("tea and snacks at the cafe"))
        assertEquals(SpendCategory.TRANSPORT, offlineClassify("bus ticket to the city"))
        assertEquals(SpendCategory.ENTERTAINMENT, offlineClassify("movie night with friends"))
        assertEquals(SpendCategory.BILLS, offlineClassify("rent payment"))
        assertEquals(SpendCategory.HEALTH, offlineClassify("medicine for fever"))
        assertEquals(SpendCategory.TRAVEL, offlineClassify("flight to Goa"))
        assertEquals(SpendCategory.SHOPPING, offlineClassify("new shoes from the store"))
    }

    @Test
    fun `offlineClassify is case-insensitive`() {
        assertEquals(SpendCategory.FOOD, offlineClassify("LUNCH at the office"))
        assertEquals(SpendCategory.FOOD, offlineClassify("  DinnER  "))
    }

    @Test
    fun `offlineClassify does not match substrings`() {
        assertEquals(SpendCategory.OTHER, offlineClassify("great deal"))
        assertEquals(SpendCategory.OTHER, offlineClassify("the weather is nice"))
    }

    @Test
    fun `offlineClassify defaults to other for unknown comments`() {
        assertEquals(SpendCategory.OTHER, offlineClassify("random expense"))
        assertEquals(SpendCategory.OTHER, offlineClassify(""))
    }

    @Test
    fun `categoryFor uses persisted category over keywords`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "lunch",
            category = "TRAVEL",
        )

        assertEquals(SpendCategory.TRAVEL, categoryFor(transaction))
    }

    @Test
    fun `categoryFor falls back to offline keywords without persisted category`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "lunch",
        )

        assertEquals(SpendCategory.FOOD, categoryFor(transaction))
    }

    @Test
    fun `categoryKey uses persisted built-in category`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "lunch",
            category = "FOOD",
        )

        assertEquals(CategoryKey.BuiltIn(SpendCategory.FOOD), categoryKey(transaction))
    }

    @Test
    fun `categoryKey keeps custom category name`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "lunch",
            category = "Gifts",
        )

        assertEquals(CategoryKey.Custom("Gifts"), categoryKey(transaction))
    }

    @Test
    fun `categoryKey classifies by keyword when category is missing`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "bus",
        )

        assertEquals(CategoryKey.BuiltIn(SpendCategory.TRANSPORT), categoryKey(transaction))
    }

    @Test
    fun `categoryKey ignores blank category values`() {
        val transaction = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(150),
            date = Date(),
            comment = "medicine",
            category = "   ",
        )

        assertEquals(CategoryKey.BuiltIn(SpendCategory.HEALTH), categoryKey(transaction))
    }

    @Test
    fun `categoryTotals aggregates custom and built-in spends separately`() {
        val spends = listOf(
            Transaction(TransactionType.SPENT, BigDecimal(10), Date(), "lunch", category = "Gifts"),
            Transaction(TransactionType.SPENT, BigDecimal(5), Date(), "bus", category = "Gifts"),
            Transaction(TransactionType.SPENT, BigDecimal(20), Date(), "movie"),
        )

        val totals = categoryTotals(spends)

        val custom = totals.first { it.first == CategoryKey.Custom("Gifts") }
        val entertainment = totals.first {
            it.first == CategoryKey.BuiltIn(SpendCategory.ENTERTAINMENT)
        }
        assertEquals(BigDecimal(15), custom.second)
        assertEquals(BigDecimal(20), entertainment.second)
        assertEquals(2, totals.size)
    }

    @Test
    fun `categoryTotals drops non-positive totals`() {
        val spends = listOf(
            Transaction(TransactionType.SPENT, BigDecimal.ZERO, Date(), "lunch", category = "Gifts"),
        )

        assertEquals(emptyList<Pair<CategoryKey, BigDecimal>>(), categoryTotals(spends))
    }

    @Test
    fun `fromStored parses enum names case-insensitively`() {
        assertEquals(SpendCategory.FOOD, SpendCategory.fromStored("FOOD"))
        assertEquals(SpendCategory.FOOD, SpendCategory.fromStored("food"))
        assertEquals(SpendCategory.TRANSPORT, SpendCategory.fromStored(" Transport "))
    }

    @Test
    fun `fromStored rejects unknown and blank values`() {
        assertNull(SpendCategory.fromStored("MYSTERY"))
        assertNull(SpendCategory.fromStored(""))
        assertNull(SpendCategory.fromStored("   "))
        assertNull(SpendCategory.fromStored(null))
    }

    @Test
    fun `parseCategoryResponse reads plain json`() {
        val result = parseCategoryResponse("""{"0":"FOOD","1":"TRANSPORT","2":"OTHER"}""")

        assertEquals(
            mapOf(0 to SpendCategory.FOOD, 1 to SpendCategory.TRANSPORT, 2 to SpendCategory.OTHER),
            result,
        )
    }

    @Test
    fun `parseCategoryResponse unwraps chat completions envelope`() {
        val envelope =
            """{"choices":[{"message":{"content":"{\"0\":\"FOOD\",\"1\":\"HEALTH\"}"}}]}"""

        val result = parseCategoryResponse(envelope)

        assertEquals(mapOf(0 to SpendCategory.FOOD, 1 to SpendCategory.HEALTH), result)
    }

    @Test
    fun `parseCategoryResponse skips unknown categories and non-numeric keys`() {
        val result = parseCategoryResponse("""{"0":"MYSTERY","foo":"FOOD","1":"TRANSPORT"}""")

        assertEquals(mapOf(1 to SpendCategory.TRANSPORT), result)
    }

    @Test
    fun `parseCategoryResponse returns empty for malformed input`() {
        assertEquals(emptyMap<Int, SpendCategory>(), parseCategoryResponse(""))
        assertEquals(emptyMap<Int, SpendCategory>(), parseCategoryResponse("no json here"))
        assertEquals(emptyMap<Int, SpendCategory>(), parseCategoryResponse("""{"0":123}"""))
        assertEquals(emptyMap<Int, SpendCategory>(), parseCategoryResponse("not a reply at all"))
    }
}
