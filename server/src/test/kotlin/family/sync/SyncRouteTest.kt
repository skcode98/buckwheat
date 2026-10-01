package family.sync

import family.sync.db.setUuid
import family.sync.sync.MAX_PULL_ROWS
import family.sync.sync.MAX_TEXT_LENGTH
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncRouteTest {

    @Test
    fun anUnauthenticatedSyncIsRejected() = runServer {
        val response = postJson("/v1/sync", """{"cursor":0,"changes":[]}""")

        assertEquals(401, response.status.value)
        assertEquals("unauthenticated", response.field("error"))
    }

    @Test
    fun aPushedRecordIsAcceptedAndComesBack() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.ownerToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        val record = response.json().recordAt(0)
        assertEquals(id, record.text("id"))
        assertEquals("transactions", record.text("table"))
        assertEquals(2, record.text("version")?.toInt())
        assertEquals(family.ownerMemberId, record.text("memberId"))
    }

    @Test
    fun thePayloadSurvivesTheRoundTrip() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.ownerToken,
        )

        val payload = response.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("12.50", payload.text("value"))
        assertEquals("1700000000000", payload.text("spentAt"))
        assertEquals("coffee", payload.text("comment"))
        assertEquals("Food", payload.text("category"))
        assertEquals("SPENT", payload.text("type"))
    }

    @Test
    fun theCursorStaysPutWhenThereIsNothingToPull() = runServer {
        val family = newFamily()

        val response = postJson("/v1/sync", syncBody(0), family.ownerToken)

        assertEquals("0", response.json().text("cursor"))
        assertEquals(emptyList<String>(), response.json().recordKeys())
        assertEquals("false", response.json().text("hasMore"))
    }

    @Test
    fun theCursorAdvancesToTheSeqOfTheRecordsItSent() = runServer {
        val family = newFamily()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", first, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", second, 1, 2000L, spentPayload())),
            family.ownerToken,
        )

        val records = response.json().records().map { it.jsonObject }
        assertEquals(2, records.size)
        val highestSeq = records.map { record -> record.text("seq")?.toLong() ?: 0L }.max()
        assertTrue("expected a positive seq", highestSeq > 0L)
        assertEquals(highestSeq.toString(), response.json().text("cursor"))
    }

    @Test
    fun aRecordPushedByOneMemberIsReadableByTheOther() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson("/v1/sync", syncBody(0), family.guestToken)

        assertEquals(listOf("transactions:$id"), response.json().recordKeys())
        assertEquals(family.ownerMemberId, response.json().recordAt(0).text("memberId"))
    }

    @Test
    fun aDifferentFamilyCannotSeeTheRecord() = runServer {
        val family = newFamily()
        val stranger = newFamily("Stranger")
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson("/v1/sync", syncBody(0), stranger.ownerToken)

        assertEquals(emptyList<String>(), response.json().recordKeys())
    }

    @Test
    fun aRecordCannotBeOverwrittenByAnotherFamily() = runServer {
        val family = newFamily()
        val stranger = newFamily("Stranger")
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val attack = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 2000L, spentPayload("99.99", "sneaky"))),
            stranger.ownerToken,
        )

        assertEquals(200, attack.status.value)
        assertEquals(emptyList<String>(), attack.json().acceptedKeys())
        val conflict = attack.json().conflicts().single()
        assertEquals(id, conflict.text("id"))
        assertEquals("transactions", conflict.text("table"))
        assertEquals("cross_family_write", conflict.text("reason"))
        // The row lives in another family, so naming its owner would leak it.
        assertNull(conflict.text("wonByMemberId"))

        val response = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val payload = response.json().recordAt(0)["payload"]?.jsonObject
        assertEquals("12.50", payload?.get("value")?.jsonPrimitive?.content)
        assertEquals("coffee", payload?.get("comment")?.jsonPrimitive?.content)
    }

    @Test
    fun aCrossFamilyCollisionDoesNotSinkTheRestOfTheBatch() = runServer {
        val family = newFamily()
        val stranger = newFamily("Stranger")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val own = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", first, 1, 1000L, spentPayload())), family.ownerToken)
        postJson("/v1/sync", syncBody(0, change("transactions", second, 1, 1000L, spentPayload())), family.ownerToken)

        val attack = postJson(
            "/v1/sync",
            syncBody(
                0,
                change("transactions", first, 1, 2000L, spentPayload("99.99", "sneaky")),
                change("transactions", own, 1, 2000L, spentPayload("1.00", "mine")),
                change("transactions", second, 1, 2000L, spentPayload("99.99", "sneaky")),
            ),
            stranger.ownerToken,
        )

        assertEquals(200, attack.status.value)
        assertEquals(listOf("transactions:$own"), attack.json().acceptedKeys())
        assertEquals(
            listOf("cross_family_write", "cross_family_write"),
            attack.json().conflicts().map { it.text("reason") },
        )
        assertEquals(
            listOf(first, second),
            attack.json().conflicts().map { it.text("id") },
        )

        // The stranger's own change committed, and the victim family's rows are untouched.
        assertEquals(1, postJson("/v1/sync", syncBody(0), stranger.ownerToken).json().records().size)
        assertEquals("coffee", postJson("/v1/sync", syncBody(0), family.ownerToken).json().recordAt(0)["payload"]
            ?.jsonObject?.text("comment"))
    }

    @Test
    fun aMalformedChangeStopsTheBatchBeforeAnythingIsWritten() = runServer {
        val family = newFamily()
        val good = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change("transactions", good, 1, 1000L, spentPayload()),
                change("transactions", UUID.randomUUID().toString(), 1, 1000L, """{"type":"SPENT"}"""),
            ),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("payload_incomplete", response.field("error"))

        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        assertEquals(emptyList<String>(), pull.json().recordKeys())
    }

    @Test
    fun aStaleWriteIsRejectedAndNamesTheWinner() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 5000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.guestToken,
        )

        val body = response.json()
        assertEquals(emptyList<String>(), body.acceptedKeys())
        val conflict = body.conflicts()[0]
        assertEquals(id, conflict.text("id"))
        assertEquals("transactions", conflict.text("table"))
        assertEquals("stale_version", conflict.text("reason"))
        assertEquals(family.ownerMemberId, conflict.text("wonByMemberId"))
    }

    @Test
    fun aRejectedWriteLeavesTheStoredValueAlone() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 5000L, spentPayload("12.50", "coffee"))), family.ownerToken)
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload("99.99", "sneaky"))), family.guestToken)
        val response = postJson("/v1/sync", syncBody(0), family.ownerToken)

        val payload = response.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("12.50", payload.text("value"))
        assertEquals("coffee", payload.text("comment"))
    }

    @Test
    fun aTombstoneIsStoredAndReturned() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload(), deletedAt = 2000L)),
            family.ownerToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        assertEquals("2000", response.json().recordAt(0).text("deletedAt"))
    }

    @Test
    fun aTombstoneWithAnEmptyPayloadIsAccepted() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        // Seed the row so this exercises the guarded UPDATE path rather than the absent-row skip.
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, "{}", deletedAt = 5000L)),
            family.ownerToken,
        )

        assertEquals(200, response.status.value)
        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        val record = response.json().recordAt(0)
        assertEquals("5000", record.text("deletedAt"))
        // Nothing about the deleted row's contents comes from the empty payload.
        assertEquals("SPENT", record["payload"]!!.jsonObject.text("type"))
        assertEquals("12.50", record["payload"]!!.jsonObject.text("value"))
    }

    @Test
    fun aTombstoneForARowTheServerNeverSawIsAcceptedAndWritesNothing() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        val before = countRows("archived_transactions", family.familyId)

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("archived_transactions", id, 1, 1000L, "{}", deletedAt = 2000L)),
            family.ownerToken,
        )

        assertEquals(200, response.status.value)
        assertEquals(listOf("archived_transactions:$id"), response.json().acceptedKeys())
        assertEquals(emptyList<JsonObject>(), response.json().conflicts())
        // No row was invented, so the period_id foreign key was never reached.
        assertEquals(before, countRows("archived_transactions", family.familyId))
        // A skipped tombstone consumes no sequence value, so it cannot disturb the cursor.
        assertEquals("0", response.json().text("cursor"))
        assertEquals(emptyList<String>(), response.json().recordKeys())
    }

    @Test
    fun aTombstoneForATagTheServerNeverSawIsAcceptedAndWritesNothing() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        val before = countRows("saved_tags", family.familyId)

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("saved_tags", id, 1, 1000L, "{}", deletedAt = 2000L)),
            family.ownerToken,
        )

        assertEquals(200, response.status.value)
        assertEquals(listOf("saved_tags:$id"), response.json().acceptedKeys())
        assertEquals(emptyList<JsonObject>(), response.json().conflicts())
        assertEquals(before, countRows("saved_tags", family.familyId))
        assertEquals("0", response.json().text("cursor"))
        assertEquals(emptyList<String>(), response.json().recordKeys())
    }

    @Test
    fun aTombstoneLeavesTheStoredBusinessValuesAlone() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        postJson("/v1/sync", syncBody(0, change("transactions", id, 2, 2000L, "{}", deletedAt = 2000L)), family.ownerToken)

        val payload = postJson("/v1/sync", syncBody(0), family.ownerToken).json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("SPENT", payload.text("type"))
        assertEquals("12.50", payload.text("value"))
        assertEquals("coffee", payload.text("comment"))
    }

    @Test
    fun anArchivedTombstoneWithAnEmptyPayloadIsAccepted() = runServer {
        val family = newFamily()
        val periodId = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()

        val period = postJson(
            "/v1/sync",
            syncBody(0, change("budget_periods", periodId, 1, 1000L, periodPayload())),
            family.ownerToken,
        )
        assertEquals(listOf("budget_periods:$periodId"), period.json().acceptedKeys())

        val archived = postJson(
            "/v1/sync",
            syncBody(0, change("archived_transactions", id, 1, 1000L, archivedPayload(periodId))),
            family.ownerToken,
        )
        assertEquals(archived.json().toString(), 200, archived.status.value)
        assertEquals(listOf("archived_transactions:$id"), archived.json().acceptedKeys())

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("archived_transactions", id, 2, 2000L, "{}", deletedAt = 2000L)),
            family.ownerToken,
        )

        assertEquals(200, response.status.value)
        assertEquals(listOf("archived_transactions:$id"), response.json().acceptedKeys())
        val record = response.json().records()
            .map { it.jsonObject }
            .single { it.text("id") == id }
        assertEquals("2000", record.text("deletedAt"))
        assertEquals(periodId, record["payload"]!!.jsonObject.text("periodId"))
    }

    @Test
    fun pullingTheSameWindowTwiceReturnsTheSameRecord() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val first = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val second = postJson("/v1/sync", syncBody(0), family.ownerToken)

        assertEquals(first.json().recordAt(0).text("seq"), second.json().recordAt(0).text("seq"))
        assertEquals(listOf(id), second.json().records().map { it.jsonObject.text("id") })
    }

    @Test
    fun aRecordAlreadySeenIsNotSentAgainWhenTheCursorIsAdvanced() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val caughtUp = postJson("/v1/sync", syncBody(0), family.ownerToken).field("cursor").toLong()
        val response = postJson("/v1/sync", syncBody(caughtUp), family.ownerToken)

        assertEquals(emptyList<String>(), response.json().recordKeys())
        assertEquals(caughtUp.toString(), response.json().text("cursor"))
    }

    @Test
    fun aPullIsCappedAndPagesUntilNothingIsLeft() = runServer {
        val family = newFamily()
        val pushed = (1..(MAX_PULL_ROWS + 1)).map { index ->
            change("saved_tags", UUID.randomUUID().toString(), 1, 1000L, """{"name":"tag-$index"}""")
        }

        val first = postJson("/v1/sync", syncBody(0, *pushed.toTypedArray()), family.ownerToken)
        assertEquals(200, first.status.value)
        assertEquals(MAX_PULL_ROWS, first.json().records().size)
        assertEquals("true", first.json().text("hasMore"))

        val seen = first.json().recordKeys().toMutableList()
        var cursor = first.field("cursor").toLong()
        var hasMore = true
        var pages = 0
        while (hasMore && pages < 5) {
            val page = postJson("/v1/sync", syncBody(cursor), family.ownerToken)
            assertEquals(200, page.status.value)
            seen.addAll(page.json().recordKeys())
            cursor = page.field("cursor").toLong()
            hasMore = page.json().text("hasMore") == "true"
            pages++
        }

        assertEquals(MAX_PULL_ROWS + 1, seen.size)
        assertEquals(MAX_PULL_ROWS + 1, seen.toSet().size)
        assertEquals("false", postJson("/v1/sync", syncBody(cursor), family.ownerToken).json().text("hasMore"))
    }

    @Test
    fun anUnknownTableIsRejected() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("nonsense", id, 1, 1000L, spentPayload())),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("unknown_table", response.field("error"))
    }

    @Test
    fun aTagWithNoMemberColumnCarriesNoMember() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("saved_tags", id, 1, 1000L, """{"name":"work"}""")),
            family.ownerToken,
        )

        assertEquals(listOf("saved_tags:$id"), response.json().acceptedKeys())
        assertEquals(null, response.json().recordAt(0).text("memberId"))
    }

    @Test
    fun aSavingsGoalNameSurvivesTheRoundTrip() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("savings_goals", id, 1, 1000L, goalPayload("Holiday"))),
            family.ownerToken,
        )

        val payload = response.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("Holiday", payload.text("name"))
    }

    @Test
    fun aSavingsGoalNameReachesTheOtherMember() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson(
            "/v1/sync",
            syncBody(0, change("savings_goals", id, 1, 1000L, goalPayload("Holiday"))),
            family.ownerToken,
        )
        val response = postJson("/v1/sync", syncBody(0), family.guestToken)

        val payload = response.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("Holiday", payload.text("name"))
    }

    @Test
    fun aSavingsGoalWithoutANameIsRejected() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "savings_goals",
                    id,
                    1,
                    1000L,
                    """{"targetAmount":"100.00","currentAmount":"0","deadline":null,""" +
                        """"createdAt":1700000000000,"completed":false}""",
                ),
            ),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("payload_incomplete", response.field("error"))
    }

    @Test
    fun anUnknownTransactionTypeIsRejected() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload().replace("SPENT", "TRANSFER"))),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("payload_invalid", response.field("error"))
    }

    @Test
    fun anOversizedCommentIsRejected() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload(comment = "x".repeat(MAX_TEXT_LENGTH + 1)))),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("payload_invalid", response.field("error"))
    }

    @Test
    fun aNumericPostgresWouldRejectIsRejected() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload(value = "1e99999999"))),
            family.ownerToken,
        )

        assertEquals(400, response.status.value)
        assertEquals("payload_invalid", response.field("error"))
    }
}

private data class Family(
    val familyId: String,
    val ownerMemberId: String,
    val ownerToken: String,
    val guestMemberId: String,
    val guestToken: String,
)

private suspend fun ApplicationTestBuilder.newFamily(ownerName: String = "Owner"): Family {
    val created = postJson("/v1/family/create", """{"displayName":"$ownerName"}""")
    val familyId = created.field("familyId")
    val ownerToken = created.field("token")
    val ownerMemberId = created.field("memberId")

    val code = postJson("/v1/family/invite", "{}", ownerToken).field("code")
    val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"Guest"}""")

    return Family(
        familyId = familyId,
        ownerMemberId = ownerMemberId,
        ownerToken = ownerToken,
        guestMemberId = joined.field("memberId"),
        guestToken = joined.field("token"),
    )
}

private fun spentPayload(value: String = "12.50", comment: String = "coffee"): String =
    """{"type":"SPENT","value":"$value","spentAt":1700000000000,"comment":"$comment","category":"Food"}"""

private fun archivedPayload(periodId: String): String =
    """{"type":"SPENT","value":"12.50","spentAt":1700000000000,"comment":"coffee",""" +
        """"category":"Food","periodId":"$periodId"}"""

private fun periodPayload(): String =
    """{"budget":"100.00","startDate":1700000000000,"finishDate":1700086400000,"actualFinishDate":null,""" +
        """"currency":"EUR","totalSpent":"12.50","isImported":false}"""

private fun goalPayload(name: String = "Holiday"): String =
    """{"name":"$name","targetAmount":"100.00","currentAmount":"0","deadline":null,""" +
        """"createdAt":1700000000000,"completed":false}"""

private fun change(
    table: String,
    id: String,
    version: Int,
    updatedAt: Long,
    payload: String,
    deletedAt: Long? = null,
): String =
    """{"table":"$table","id":"$id","version":$version,"updatedAt":$updatedAt,"deletedAt":$deletedAt,"payload":$payload}"""

private fun syncBody(cursor: Long, vararg changes: String): String =
    """{"cursor":$cursor,"changes":[${changes.joinToString(",")}]}"""

private fun JsonObject.records(): JsonArray = this["records"]?.jsonArray ?: JsonArray(emptyList())

private fun JsonObject.recordAt(index: Int): JsonObject = records()[index].jsonObject

/** The wire shape is table+id keyed, so tests read the pair back rather than the bare uuid. */
private fun JsonObject.acceptedKeys(): List<String> = keysOf("accepted")

private fun JsonObject.conflicts(): List<JsonObject> =
    (this["conflicts"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }

private fun JsonObject.recordKeys(): List<String> = records().map { it.jsonObject.key() }

private fun JsonObject.keysOf(name: String): List<String> =
    (this[name]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject.key() }

private fun JsonObject.key(): String = "${text("table")}:${text("id")}"

/** Scoped to a family so a shared test database cannot make the count drift. */
private fun countRows(table: String, familyId: String): Int =
    TestDatabase.dataSource.connection.use { connection ->
        connection.prepareStatement("select count(*) from $table where family_id = ?::uuid").use { statement ->
            statement.setUuid(1, familyId)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content