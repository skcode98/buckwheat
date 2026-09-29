package com.danilkinkin.buckwheat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolRecordsTest {

    @Test
    fun anEnrolledRecordCarriesTheOwnersMemberId() {
        val result = enrolRecords(
            records = listOf(local()),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals("member-9", result.single().memberId)
    }

    @Test
    fun anEnrolledRecordCarriesTheFamilyId() {
        val result = enrolRecords(
            records = listOf(local()),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals("family-1", result.single().familyId)
    }

    @Test
    fun anEnrolledRecordIsNotDirty() {
        val result = enrolRecords(
            records = listOf(local(dirty = true)),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertTrue(result.single().dirty.not())
    }

    @Test
    fun anEnrolledRecordIsStampedWithTheEnrolmentTime() {
        val result = enrolRecords(
            records = listOf(local()),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals(1000L, result.single().updatedAt)
    }

    @Test
    fun anEnrolledRecordKeepsItsId() {
        val result = enrolRecords(
            records = listOf(local(id = "rec-a")),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals("rec-a", result.single().id)
    }

    @Test
    fun anEnrolledRecordKeepsItsPayload() {
        val result = enrolRecords(
            records = listOf(local(payload = "local-payload")),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals("local-payload", result.single().payload)
    }

    @Test
    fun anEnrolledRecordStartsAtVersionOne() {
        val result = enrolRecords(
            records = listOf(local(version = 4)),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals(1, result.single().version)
    }

    @Test
    fun anEnrolledRecordStartsWithNoServerSequence() {
        val result = enrolRecords(
            records = listOf(local(syncSeq = 55L)),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals(0L, result.single().syncSeq)
    }

    @Test
    fun anEnrolledRecordIsNotDeleted() {
        val result = enrolRecords(
            records = listOf(local(deletedAt = 500L)),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertNull(result.single().deletedAt)
    }

    @Test
    fun enrollingNothingProducesNothing() {
        val result = enrolRecords(
            records = emptyList(),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun everyRecordIsEnrolled() {
        val result = enrolRecords(
            records = listOf(
                local(id = "rec-a"),
                local(id = "rec-b"),
                local(id = "rec-c"),
            ),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals(listOf("rec-a", "rec-b", "rec-c"), result.map { it.id })
        assertTrue(result.all { it.memberId == "member-9" })
    }

    @Test
    fun theRecordOrderIsPreserved() {
        val result = enrolRecords(
            records = listOf(local(id = "rec-z"), local(id = "rec-a")),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals(listOf("rec-z", "rec-a"), result.map { it.id })
    }

    @Test
    fun theTableIsUntouched() {
        val result = enrolRecords(
            records = listOf(local(table = "budget_periods")),
            memberId = "member-9",
            familyId = "family-1",
            enrolledAt = 1000L,
        )

        assertEquals("budget_periods", result.single().table)
    }

    private fun local(
        id: String = "rec-a",
        table: String = "transactions",
        updatedAt: Long = 100L,
        version: Int = 1,
        deletedAt: Long? = null,
        payload: String = "local-payload",
        dirty: Boolean = false,
        memberId: String? = null,
        familyId: String? = null,
        syncSeq: Long = 0L,
    ) = LocalRecord(
        table = table,
        id = id,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        dirty = dirty,
        memberId = memberId,
        familyId = familyId,
        syncSeq = syncSeq,
    )
}
