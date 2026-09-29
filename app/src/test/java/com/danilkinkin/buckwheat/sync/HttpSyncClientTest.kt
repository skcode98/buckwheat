package com.danilkinkin.buckwheat.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpSyncClientTest {

    private fun local(
        id: String = "rec-1",
        version: Int = 1,
        updatedAt: Long = 1000L,
        deletedAt: Long? = null,
        payload: String = """{"type":"SPENT","value":"12.50"}""",
    ) = LocalRecord(
        table = "transactions",
        id = id,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        payload = payload,
        dirty = true,
        memberId = "member-1",
    )

    @Test
    fun theEndpointIsTheSyncPathOnTheBaseUrl() {
        assertEquals("https://sync.example.com/v1/sync", syncEndpoint("https://sync.example.com"))
        assertEquals("https://sync.example.com/v1/sync", syncEndpoint("https://sync.example.com/"))
        assertEquals("https://sync.example.com/v1/sync", syncEndpoint("  https://sync.example.com/  "))
    }

    @Test
    fun anEndpointThatIsAlreadyTheSyncPathIsUsedAsIs() {
        assertEquals(
            "https://sync.example.com/v1/sync",
            syncEndpoint("https://sync.example.com/v1/sync"),
        )
    }

    @Test
    fun theRequestCarriesTheCursorAndEveryChange() {
        val body = encodeSyncRequest(SyncRequest(cursor = 42L, changes = listOf(local())))

        assertEquals(42L, body.getLong("cursor"))
        val changes = body.getJSONArray("changes")
        assertEquals(1, changes.length())
        val change = changes.getJSONObject(0)
        assertEquals("transactions", change.getString("table"))
        assertEquals("rec-1", change.getString("id"))
        assertEquals(1, change.getInt("version"))
        assertEquals(1000L, change.getLong("updatedAt"))
        assertTrue(change.has("deletedAt"))
        assertTrue(change.isNull("deletedAt"))
    }

    @Test
    fun aChangeCarriesItsPayloadAsAnObjectNotAQuotedString() {
        val body = encodeSyncRequest(SyncRequest(cursor = 0L, changes = listOf(local())))

        val payload = body.getJSONArray("changes").getJSONObject(0).getJSONObject("payload")
        assertEquals("SPENT", payload.getString("type"))
        assertEquals("12.50", payload.getString("value"))
    }

    @Test
    fun aTombstoneIsSentWithItsDeletedAt() {
        val body = encodeSyncRequest(
            SyncRequest(cursor = 0L, changes = listOf(local(version = 3, deletedAt = 2000L)))
        )

        assertEquals(2000L, body.getJSONArray("changes").getJSONObject(0).getLong("deletedAt"))
    }

    @Test
    fun anEmptyChangeListIsStillAnArray() {
        val body = encodeSyncRequest(SyncRequest(cursor = 7L, changes = emptyList()))

        assertEquals(0, body.getJSONArray("changes").length())
    }

    @Test
    fun theResponseDecodesEveryField() {
        val response = decodeSyncResponse(
            """
            {
              "cursor": 17,
              "accepted": ["rec-1"],
              "records": [
                {
                  "table": "transactions",
                  "id": "rec-1",
                  "seq": 17,
                  "updatedAt": 1000,
                  "version": 2,
                  "deletedAt": null,
                  "payload": {"type":"SPENT"},
                  "memberId": "member-1"
                }
              ],
              "conflicts": [
                {"table":"saved_tags","id":"rec-9","wonByMemberId":"member-2"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(17L, response.cursor)
        assertEquals(listOf("rec-1"), response.accepted)
        assertEquals(1, response.records.size)
        val record = response.records.first()
        assertEquals("transactions", record.table)
        assertEquals("rec-1", record.id)
        assertEquals(17L, record.seq)
        assertEquals(1000L, record.updatedAt)
        assertEquals(2, record.version)
        assertEquals("SPENT", JSONObject(record.payload).getString("type"))
        assertEquals("member-1", record.memberId)
        assertEquals(1, response.conflicts.size)
        assertEquals("member-2", response.conflicts.first().wonByMemberId)
    }

    @Test
    fun anOmittedDeletedAtDecodesToNull() {
        val record = decodeSyncResponse(
            """
            {"cursor":1,"accepted":[],"records":[
              {"table":"saved_tags","id":"t-1","seq":1,"updatedAt":5,"version":1,
               "payload":{"name":"work"}}],"conflicts":[]}
            """.trimIndent()
        ).records.first()

        assertNull(record.deletedAt)
        assertNull(record.memberId)
    }

    @Test
    fun anExplicitJsonNullDecodesToNullRatherThanTheStringNull() {
        val record = decodeSyncResponse(
            """
            {"cursor":1,"accepted":[],"records":[
              {"table":"saved_tags","id":"t-1","seq":1,"updatedAt":5,"version":1,
               "deletedAt":null,"payload":{"name":"work"},"memberId":null}],"conflicts":[]}
            """.trimIndent()
        ).records.first()

        assertNull(record.deletedAt)
        assertNull(record.memberId)
    }

    @Test
    fun aConflictWithoutAWinnerDecodesToAnEmptyWinner() {
        val response = decodeSyncResponse(
            """{"cursor":1,"accepted":[],"records":[],"conflicts":[
                 {"table":"saved_tags","id":"t-1"}]}"""
        )

        assertEquals("", response.conflicts.first().wonByMemberId)
    }

    @Test
    fun aResponseMissingTheArraysDecodesToEmpty() {
        val response = decodeSyncResponse("""{"cursor":9}""")

        assertEquals(9L, response.cursor)
        assertEquals(emptyList<String>(), response.accepted)
        assertEquals(emptyList<WireRecord>(), response.records)
        assertEquals(emptyList<ConflictNotice>(), response.conflicts)
    }

    @Test
    fun aResponseMissingTheCursorFallsBackToZero() {
        val response = decodeSyncResponse("""{"accepted":[],"records":[],"conflicts":[]}""")

        assertEquals(0L, response.cursor)
    }

    @Test
    fun aRecordWithoutAPayloadDecodesToAnEmptyPayload() {
        val record = decodeSyncResponse(
            """{"cursor":1,"accepted":[],"records":[
                 {"table":"saved_tags","id":"t-1","seq":1,"updatedAt":5,"version":1}],
               "conflicts":[]}"""
        ).records.first()

        assertEquals("{}", record.payload)
    }

    @Test
    fun anErrorBodyYieldsItsCode() {
        assertEquals("unauthenticated", errorCodeOf("""{"error":"unauthenticated"}"""))
        assertEquals("sync_lock_timeout", errorCodeOf("""{"error":"sync_lock_timeout"}"""))
    }

    @Test
    fun aNonJsonErrorBodyHasNoCode() {
        assertNull(errorCodeOf("<html><body>Internal Server Error</body></html>"))
        assertNull(errorCodeOf(""))
        assertNull(errorCodeOf("""{"error":null}"""))
        assertNull(errorCodeOf("""{"error":""}"""))
        assertNull(errorCodeOf("""{"message":"nope"}"""))
    }

    @Test
    fun aMalformedResponseBodyIsRejected() {
        val failure = try {
            decodeSyncResponse("not json at all")
            null
        } catch (e: IOException) {
            e
        }

        assertTrue(failure?.message?.contains("response") == true)
    }
}
