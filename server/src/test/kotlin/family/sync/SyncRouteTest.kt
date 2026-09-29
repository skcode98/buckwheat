package family.sync

import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

        assertEquals(listOf(id), response.json().texts("accepted"))
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
        assertEquals(emptyList<String>(), response.json().records().map { it.jsonObject.text("id") })
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

        assertEquals(listOf(id), response.json().records().map { it.jsonObject.text("id") })
        assertEquals(family.ownerMemberId, response.json().recordAt(0).text("memberId"))
    }

    @Test
    fun aDifferentFamilyCannotSeeTheRecord() = runServer {
        val family = newFamily()
        val stranger = newFamily("Stranger")
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.ownerToken)
        val response = postJson("/v1/sync", syncBody(0), stranger.ownerToken)

        assertEquals(emptyList<String>(), response.json().records().map { it.jsonObject.text("id") })
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

        assertEquals(403, attack.status.value)
        assertEquals("cross_family_write", attack.json()["error"]?.jsonPrimitive?.content)

        val response = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val payload = response.json().recordAt(0)["payload"]?.jsonObject
        assertEquals("12.50", payload?.get("value")?.jsonPrimitive?.content)
        assertEquals("coffee", payload?.get("comment")?.jsonPrimitive?.content)
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
        assertEquals(emptyList<String>(), body.texts("accepted"))
        val conflict = body["conflicts"]!!.jsonArray[0].jsonObject
        assertEquals(id, conflict.text("id"))
        assertEquals("transactions", conflict.text("table"))
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

        assertEquals(listOf(id), response.json().texts("accepted"))
        assertEquals("2000", response.json().recordAt(0).text("deletedAt"))
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

        assertEquals(emptyList<String>(), response.json().records().map { it.jsonObject.text("id") })
        assertEquals(caughtUp.toString(), response.json().text("cursor"))
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

        assertEquals(listOf(id), response.json().texts("accepted"))
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

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content

private fun JsonObject.texts(name: String): List<String> = this[name].jsonArrayText()
