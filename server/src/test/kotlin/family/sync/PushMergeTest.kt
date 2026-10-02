package family.sync.sync

import family.sync.family.BadRequestException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PushMergeTest {

    @Test
    fun aBrandNewRecordIsAccepted() {
        val decision = decidePush(
            stored = null,
            incoming = push(version = 1, updatedAt = 500),
        )

        assertEquals(MergeDecision.Accept(version = 2), decision)
    }

    @Test
    fun aMatchingVersionIsAccepted() {
        val decision = decidePush(
            stored = stored(version = 3, updatedAt = 500, memberId = MEMBER_B),
            incoming = push(version = 3, updatedAt = 500),
        )

        assertEquals(MergeDecision.Accept(version = 4), decision)
    }

    @Test
    fun aNewerVersionIsAccepted() {
        val decision = decidePush(
            stored = stored(version = 1, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 5, updatedAt = 100),
        )

        assertEquals(MergeDecision.Accept(version = 6), decision)
    }

    @Test
    fun aStaleVersionWithANewerTimestampWins() {
        val decision = decidePush(
            stored = stored(version = 4, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 200),
        )

        assertEquals(MergeDecision.Accept(version = 5), decision)
    }

    @Test
    fun aStaleVersionWithAnOlderTimestampLoses() {
        val decision = decidePush(
            stored = stored(version = 4, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 50),
        )

        assertEquals(MergeDecision.Reject(RejectReason.STALE_VERSION, winnerMemberId = MEMBER_B), decision)
    }

    @Test
    fun aStaleVersionWithAnEqualTimestampLoses() {
        val decision = decidePush(
            stored = stored(version = 4, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 100),
        )

        assertEquals(MergeDecision.Reject(RejectReason.STALE_VERSION, winnerMemberId = MEMBER_B), decision)
    }

    @Test
    fun aTombstoneIsAcceptedEvenWhenItIsStaleInEveryOtherWay() {
        val decision = decidePush(
            stored = stored(version = 9, updatedAt = 100, memberId = MEMBER_B, deletedAt = null),
            incoming = push(version = 1, updatedAt = 50, deletedAt = 900),
        )

        assertEquals(MergeDecision.Accept(version = 10), decision)
    }

    @Test
    fun anAcceptedWriteNeverMovesTheStoredVersionBackwards() {
        val cases = listOf(
            stored(version = 4, updatedAt = 100, memberId = MEMBER_B),
            stored(version = 9, updatedAt = 100, memberId = MEMBER_B, deletedAt = 900),
        )

        for (existing in cases) {
            val decision = decidePush(
                stored = existing,
                incoming = push(version = 1, updatedAt = 1000, deletedAt = 5000),
            )

            assertTrue(
                "stored version ${existing.version} was not advanced",
                (decision as MergeDecision.Accept).version > existing.version,
            )
        }
    }

    @Test
    fun aStoredTombstoneBeatsANewerLiveRecord() {
        val decision = decidePush(
            stored = stored(version = 9, updatedAt = 100, memberId = MEMBER_B, deletedAt = 900),
            incoming = push(version = 1, updatedAt = 9999),
        )

        assertEquals(MergeDecision.Reject(RejectReason.DELETED_REMOTELY, winnerMemberId = MEMBER_B), decision)
    }

    @Test
    fun theLosingWriteIsNamedAfterTheMemberWhoWon() {
        val decision = decidePush(
            stored = stored(version = 7, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 50),
        )

        assertEquals(MEMBER_B, (decision as MergeDecision.Reject).winnerMemberId)
    }

    @Test
    fun everyRejectionCarriesAReasonTheClientCanRender() {
        assertEquals("stale_version", RejectReason.STALE_VERSION.wire)
        assertEquals("deleted_remotely", RejectReason.DELETED_REMOTELY.wire)
        assertEquals("cross_family_write", RejectReason.CROSS_FAMILY_WRITE.wire)
    }

    @Test
    fun aTombstoneDefaultIsWritableForEveryColumnType() {
        val expected = mapOf(
            SqlType.TEXT to "",
            SqlType.NUMERIC to "0",
            SqlType.BIGINT to "0",
            SqlType.INTEGER to "0",
            SqlType.BOOLEAN to "false",
            SqlType.UUID to "00000000-0000-0000-0000-000000000000",
        )

        SyncTables.ALL.forEach { spec ->
            spec.columns.filterNot { it.nullable }.forEach { column ->
                assertEquals(
                    "${spec.name}.${column.column}",
                    expected.getValue(column.type),
                    zeroValue(column),
                )
            }
        }
    }

    @Test
    fun aTransactionPayloadIsReadInColumnOrder() {
        val values = readPayload(
            """{"type":"SPENT","value":"12.50","spentAt":1700000000000,"comment":"coffee","category":null}""",
            SyncTables.require("transactions"),
        )

        // Compared against the spec rather than a literal, because a transaction gained three optional
        // columns and a hardcoded five quietly stopped testing what it claimed to test. Reading the
        // expected order out of the spec keeps this a real ordering assertion: a column read in the
        // wrong position still fails, and adding a column no longer needs this file edited.
        assertEquals(
            listOf("SPENT", "12.50", "1700000000000", "coffee", null, null, null, null),
            values,
        )
    }

    /**
     * The point of this one is the *trailing* null, so it asserts the count against the spec and checks
     * the tail rather than a bare `assertNull(values.last())`, which passed whatever the column count
     * happened to be.
     */
    @Test
    fun aMissingNullableColumnBecomesNull() {
        val values = readPayload(TRANSACTION_PAYLOAD, SyncTables.require("transactions"))

        assertEquals(SyncTables.require("transactions").columns.size, values.size)
        assertNull(values.last())
    }

    @Test
    fun aMissingRequiredColumnIsIncomplete() {
        val code = codeThrownBy {
            readPayload("""{"type":"SPENT"}""", SyncTables.require("transactions"))
        }

        assertEquals("payload_incomplete", code)
    }

    @Test
    fun everyRealTransactionTypeIsAccepted() {
        listOf("SET_DAILY_BUDGET", "INCOME", "SPENT").forEach { type ->
            val values = readPayload(
                TRANSACTION_PAYLOAD.replace("\"type\":\"SPENT\"", "\"type\":\"$type\""),
                SyncTables.require("transactions"),
            )
            assertEquals(type, values.first())
        }
    }

    @Test
    fun anUnknownTransactionTypeIsInvalid() {
        listOf("transactions", "archived_transactions").forEach { table ->
            val code = codeThrownBy {
                readPayload(
                    TRANSACTION_PAYLOAD.replace("\"type\":\"SPENT\"", "\"type\":\"TRANSFER\""),
                    SyncTables.require(table),
                )
            }
            assertEquals("payload_invalid", code)
        }
    }

    @Test
    fun aPayloadThatIsNotAnObjectIsInvalid() {
        assertEquals("payload_invalid", codeThrownBy { readPayload("[]", SyncTables.require("transactions")) })
        assertEquals("payload_invalid", codeThrownBy { readPayload("null", SyncTables.require("transactions")) })
        assertEquals("payload_invalid", codeThrownBy { readPayload("{", SyncTables.require("transactions")) })
    }

    @Test
    fun anOversizedCommentIsInvalid() {
        val comment = "x".repeat(MAX_TEXT_LENGTH + 1)
        val code = codeThrownBy {
            readPayload(
                TRANSACTION_PAYLOAD.replace("\"coffee\"", "\"$comment\""),
                SyncTables.require("transactions"),
            )
        }

        assertEquals("payload_invalid", code)
    }

    @Test
    fun aCommentAtTheTextLimitIsStillAccepted() {
        val comment = "x".repeat(MAX_TEXT_LENGTH)
        val values = readPayload(
            TRANSACTION_PAYLOAD.replace("\"coffee\"", "\"$comment\""),
            SyncTables.require("transactions"),
        )

        assertEquals(comment, values[3])
    }

    @Test
    fun aNumericPostgresWouldRejectIsInvalid() {
        // toBigDecimalOrNull accepts all of these; numeric does not hold them, so they would have
        // surfaced as a 500 from the driver instead of a payload_invalid.
        listOf(
            "1e99999999",
            "1E-99999999",
            "99999999999999999999999999",
            "NaN",
            "Infinity",
            "12,50",
            "twelve",
            "",
            " ".repeat(MAX_NUMERIC_LENGTH + 1),
        ).forEach { value ->
            val code = codeThrownBy {
                readPayload(
                    TRANSACTION_PAYLOAD.replace("\"12.50\"", "\"" + value + "\""),
                    SyncTables.require("transactions"),
                )
            }
            assertEquals("payload_invalid", code)
        }
    }

    @Test
    fun ordinaryAmountsSurviveNumericValidation() {
        listOf("12.50", "0", "100", "0.000001", "1E+3", "-42.75", "999999999999999.999999").forEach { value ->
            val values = readPayload(
                TRANSACTION_PAYLOAD.replace("\"12.50\"", "\"$value\""),
                SyncTables.require("transactions"),
            )
            assertEquals(value, values[1])
        }
    }

    @Test
    fun anIntegerTooWideForTheColumnIsInvalid() {
        val code = codeThrownBy {
            requireValidValue(
                PayloadColumn("dayOfMonth", "day_of_month", SqlType.INTEGER, false),
                JsonPrimitive("2147483648"),
            )
        }

        assertEquals("payload_invalid", code)
    }

    @Test
    fun aBigIntAtItsLimitIsAccepted() {
        requireValidValue(
            PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
            JsonPrimitive("9223372036854775807"),
        )
    }

    private fun codeThrownBy(block: () -> Unit): String = try {
        block()
        fail("expected a BadRequestException")
        ""
    } catch (failure: BadRequestException) {
        failure.code
    }

    private fun push(
        version: Int,
        updatedAt: Long,
        deletedAt: Long? = null,
    ) = PushChange(
        table = "transactions",
        id = "rec-a",
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        payload = "{}",
    )

    private fun stored(
        version: Int,
        updatedAt: Long,
        memberId: String,
        deletedAt: Long? = null,
    ) = StoredRecord(
        id = "rec-a",
        version = version,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        memberId = memberId,
    )

    private companion object {
        const val MEMBER_B = "member-b"
        const val TRANSACTION_PAYLOAD =
            """{"type":"SPENT","value":"12.50","spentAt":1700000000000,"comment":"coffee"}"""
    }
}