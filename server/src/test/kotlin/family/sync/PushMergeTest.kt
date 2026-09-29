package family.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertEquals(MergeDecision.Reject(winnerMemberId = MEMBER_B), decision)
    }

    @Test
    fun aStaleVersionWithAnEqualTimestampLoses() {
        val decision = decidePush(
            stored = stored(version = 4, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 100),
        )

        assertEquals(MergeDecision.Reject(winnerMemberId = MEMBER_B), decision)
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

        assertEquals(MergeDecision.Reject(winnerMemberId = MEMBER_B), decision)
    }

    @Test
    fun theLosingWriteIsNamedAfterTheMemberWhoWon() {
        val decision = decidePush(
            stored = stored(version = 7, updatedAt = 100, memberId = MEMBER_B),
            incoming = push(version = 2, updatedAt = 50),
        )

        assertEquals(MEMBER_B, (decision as MergeDecision.Reject).winnerMemberId)
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
    }
}
