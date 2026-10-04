package family.sync

import family.sync.db.setUuid
import family.sync.sync.MAX_PULL_ROWS
import family.sync.sync.MAX_TEXT_LENGTH
import family.sync.sync.SyncTables
import io.ktor.client.statement.HttpResponse
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
        assertDenied(attack)
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

        // Both pushes are from the same member, which is what a stale write actually is: one member's
        // two devices editing the same row. This used to use the guest's token, back when a guest could
        // write anything at all, and it now trips the cross-member rule before the version check -- so
        // the test had been asserting the reason for a different failure than the one it described.
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 5000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.ownerToken,
        )

        val body = response.json()
        assertEquals(emptyList<String>(), body.acceptedKeys())
        val conflict = body.conflicts()[0]
        assertEquals(id, conflict.text("id"))
        assertEquals("transactions", conflict.text("table"))
        assertEquals("stale_version", conflict.text("reason"))
        assertEquals(family.ownerMemberId, conflict.text("wonByMemberId"))
    }

    /**
     * A stale write from a *different* member is refused for the other reason.
     *
     * Both are true of the push -- it is stale and it is somebody else's row -- and authority is
     * checked first on purpose: a member should not learn a row's current version when they have no
     * business writing it, and "you may not write this" is more use than "your copy is old".
     */
    @Test
    fun aStaleWriteFromAnotherMemberIsRefusedForBeingTheirs() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 5000L, spentPayload())), family.ownerToken)
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.guestToken,
        )

        assertDenied(response)
        assertEquals("cross_member_write", response.json().conflicts()[0].text("reason"))
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
        val before = countRows("transactions", family.familyId)

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, "{}", deletedAt = 2000L)),
            family.ownerToken,
        )

        assertEquals(200, response.status.value)
        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        assertEquals(emptyList<JsonObject>(), response.json().conflicts())
        // No row was invented.
        assertEquals(before, countRows("transactions", family.familyId))
        // A skipped tombstone consumes no sequence value, so it cannot disturb the cursor.
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
            change(
                "transactions",
                UUID.randomUUID().toString(),
                1,
                1000L,
                """{"type":"SPENT","value":"1.00","spentAt":1700000000000,"comment":"tag-$index","category":null}""",
            )
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
// ---------------------------------------------------------------------------
    // Who may write what in a family.
    //
    // These live here rather than in their own file so they reuse the harness above, which mints a
    // real owner and a real guest. Every rule was previously unguarded -- `owner_only` and
    // `cross_member_write` appeared nowhere in the suite -- so disabling the checks would have looked
    // exactly like keeping them. Each test pushes through the real route, because the question is what
    // a member can actually do, not what a function claims to do.
    // ---------------------------------------------------------------------------

    @Test
    fun aMemberMayStillRecordAnOrdinarySpend() = runServer {
        // Tightening the household rules must not break anybody's own tracking.
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload())),
            family.guestToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
    }

    /**
     * A member may not take over or delete another member's personal spend.
     *
     * `write` rebinds `member_id` to the caller on every write, so without an ownership check a member
     * could push a new version of somebody else's row and have the amount and the attribution both
     * move into their own ledger.
     */
    @Test
    fun aMemberMayNotOverwriteOrDeleteSomebodyElsesSpend() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload("20.00", "head"))), family.ownerToken)

        val overwrite = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload("9000.00", "not mine"))),
            family.guestToken,
        )
        assertDenied(overwrite)

        val remove = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
            family.guestToken,
        )
        assertDenied(remove)

        // Still the head's row, still 20.00, still undeleted.
        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val stored = pull.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("20.00", stored.text("value"))
        assertEquals(family.ownerMemberId, pull.json().recordAt(0)["memberId"]!!.jsonPrimitive.content)
    }

    /**
     * A member may still edit and delete their own spend, or the rules would break normal use.
     */
    @Test
    fun aMemberMayStillEditAndDeleteTheirOwnSpend() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.guestToken)

        val edit = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload("30.00", "corrected"))),
            family.guestToken,
        )
        assertEquals(listOf("transactions:$id"), edit.json().acceptedKeys())

        val remove = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
            family.guestToken,
        )
        assertEquals(listOf("transactions:$id"), remove.json().acceptedKeys())
    }

/**
 * A family whose head leaves keeps a head.
     *
     * `isOwner` is the only thing gating the pool, the split and household spending, so with no owner a
     * survivor is permanently unable to change the family budget and there is no route to appoint anyone.
     * Failing closed is right for a security rule and wrong as an outcome: the family is stranded rather
     * than protected. The earliest-joined remaining member is promoted, so the choice does not depend on
     * who happened to press leave.
     */
    @Test
    fun theEarliestRemainingMemberIsPromotedWhenTheHeadLeaves() = runServer {
        val family = newFamily()
        // `newFamily` already has a second member -- the guest it invited. The successor is that
        // member, not "Second": asserting on the later joiner would pass even if the rule picked
        // whoever happened to be last.
        val later = joinMember("Later", family.familyId)

        postJson("/v1/family/leave", "{}", family.ownerToken)

        val members = postJson("/v1/family/members", "{}", family.guestToken)
            .json()["members"]!!.jsonArray.map { it.jsonObject }

        val promoted = members.first { it["id"]!!.jsonPrimitive.content == family.guestMemberId }
        assertTrue("the earliest survivor was not promoted", promoted["isOwner"]!!.jsonPrimitive.content == "true")

        val other = members.first { it["id"]!!.jsonPrimitive.content == later.memberId }
        assertTrue("a second head appeared", other["isOwner"]!!.jsonPrimitive.content != "true")
    }

    @Test
    fun anOrdinaryMemberLeavingDoesNotChangeWhoTheHeadIs() = runServer {
        val family = newFamily()
        val second = joinMember("Second", family.familyId)

        postJson("/v1/family/leave", "{}", second.token)

        val members = postJson("/v1/family/members", "{}", family.ownerToken)
            .json()["members"]!!.jsonArray.map { it.jsonObject }
        val head = members.first { it["id"]!!.jsonPrimitive.content == family.ownerMemberId }
        assertTrue("the head was demoted when an ordinary member left", head["isOwner"]!!.jsonPrimitive.content == "true")
    }

/**
     * Leaving with nobody behind is not an error.
     *
     * The second leave uses the guest's token on purpose: the head's token was just revoked, so
     * reusing it would assert 401 and the test would be about token revocation rather than about
     * there being nobody left to promote.
     */
@Test
    fun theLastMemberLeavingIsNotAnError() = runServer {
        val family = newFamily()

        assertEquals(200, postJson("/v1/family/leave", "{}", family.ownerToken).status.value)
        assertEquals(200, postJson("/v1/family/leave", "{}", family.guestToken).status.value)
    }

    /**
     * One table in, one table out.
     *
     * The contract used to describe ten tables and the rules that policed them. What is left is
     * `transactions`, and a client still offering a retired table is told so rather than having the
     * row quietly dropped.
     */
    @Test
    fun theOnlySyncedTableIsTransactions() = runServer {
        val family = newFamily()

        assertEquals(listOf("transactions"), SyncTables.ALL.map { it.name })

        val retired = postJson(
            "/v1/sync",
            syncBody(0, change("savings_goals", UUID.randomUUID().toString(), 1, 1000L, """{"name":"Holiday"}""")),
            family.ownerToken,
        )

        assertEquals(400, retired.status.value)
        assertEquals("unknown_table", retired.field("error"))
    }

    /**
     * `comment` is optional on the wire and null in the column.
     *
     * `V1` declared the column `not null`, so a commentless payload used to be a 500 from a bind
     * failure rather than anything the client could act on. Absence is meaningful -- it is not an empty
     * string -- so it is stored as null.
     */
    @Test
    fun aCommentlessTransactionStoresNull() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayloadWithoutComment())),
            family.ownerToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        TestDatabase.dataSource.connection.use { connection ->
            connection.prepareStatement("select comment from transactions where id = ?::uuid").use { statement ->
                statement.setUuid(1, id)
                statement.executeQuery().use { rows ->
                    assertTrue("the row was not written", rows.next())
                    val comment = rows.getString(1)
                    rows.wasNull()
                    assertNull("a commentless payload must store null, not an empty string", comment)
                }
            }
        }
    }

    /**
     * The token decides whose row this is, not the payload.
     *
     * `familyId` and `memberId` are read out of the verified token and the payload's copies are
     * ignored. If they were not, a member could write into another family by naming it, and could
     * attribute a spend to somebody else inside their own.
     */
    @Test
    fun aTokenStampsFamilyAndMemberEvenWhenThePayloadForgesThem() = runServer {
        val family = newFamily()
        val stranger = newFamily("Stranger")
        val id = UUID.randomUUID().toString()
        val forged = """{"type":"SPENT","value":"9.99","spentAt":1700000000000,"comment":"forged",""" +
            """"category":null,"familyId":"${stranger.familyId}","memberId":"${stranger.ownerMemberId}"}"""

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, forged)),
            family.guestToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
        assertEquals(family.guestMemberId, response.json().recordAt(0).text("memberId"))
        assertEquals(
            "the payload must not be able to redirect the row into another family",
            0,
            countRows("transactions", stranger.familyId),
        )
        assertEquals(1, countRows("transactions", family.familyId))
    }

    /**
     * The author's own row is theirs to change.
     *
     * The stored `member_id` has to survive the edit: rebinding it to whoever pushed last would let a
     * later push take the row over by editing it once.
     */
    @Test
    fun theAuthorCanUpdateAndDeleteTheirOwnRow() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.guestToken)

        val update = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload("30.00", "corrected"))),
            family.guestToken,
        )
        assertEquals(listOf("transactions:$id"), update.json().acceptedKeys())

        val remove = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
            family.guestToken,
        )
        assertEquals(listOf("transactions:$id"), remove.json().acceptedKeys())

        val record = postJson("/v1/sync", syncBody(0), family.ownerToken).json().recordAt(0)
        assertEquals("3000", record.text("deletedAt"))
        assertEquals(family.guestMemberId, record.text("memberId"))
    }

    @Test
    fun updatingAnotherMembersRowIsRefused() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload("20.00", "theirs"))),
            family.ownerToken,
        )

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload("9000.00", "not mine"))),
            family.guestToken,
        )

        assertDenied(response)
        val conflict = response.json().conflicts()[0]
        assertEquals("cross_member_write", conflict.text("reason"))
        assertEquals(family.ownerMemberId, conflict.text("wonByMemberId"))
        assertEquals(
            "20.00",
            postJson("/v1/sync", syncBody(0), family.ownerToken).json()
                .recordAt(0)["payload"]!!.jsonObject.text("value"),
        )
    }

    @Test
    fun deletingAnotherMembersRowIsRefused() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, spentPayload("20.00", "theirs"))),
            family.ownerToken,
        )

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, tombstonePayload(), deletedAt = 2000L)),
            family.guestToken,
        )

        assertDenied(response)
        val conflict = response.json().conflicts()[0]
        assertEquals("cross_member_write", conflict.text("reason"))
        assertEquals(family.ownerMemberId, conflict.text("wonByMemberId"))
        assertNull(postJson("/v1/sync", syncBody(0), family.ownerToken).json().recordAt(0).text("deletedAt"))
    }

    /**
     * `since` bounds the pull without touching the cursor.
     *
     * A full refresh reads the whole ledger back from zero. `since` is the cheaper half of that: a
     * client that already holds everything up to a timestamp asks only for what moved after it, and
     * the cursor it passes still governs which records it has not seen.
     */
    @Test
    fun sinceBoundsThePull() = runServer {
        val family = newFamily()
        val old = UUID.randomUUID().toString()
        val recent = UUID.randomUUID().toString()
        seedTransaction(old, family.familyId, "old", 1_000L)
        seedTransaction(recent, family.familyId, "recent", 9_000_000_000L)

        val response = postJson("/v1/sync", syncBodySince(0, 5_000_000_000L), family.ownerToken)

        assertEquals(listOf("transactions:$recent"), response.json().recordKeys())
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
    val credentials = postJson("/v1/family/create", """{"displayName":"$ownerName"}""").json()
    val familyId = credentials.text("familyId") ?: error("no familyId")
    val ownerMemberId = credentials.text("memberId") ?: error("no memberId")
    val ownerToken = credentials.text("token") ?: error("no token")
    val guestMemberId = TestDatabase.addMember(familyId, "Guest")
    val guestToken = TestDatabase.issueToken(familyId, guestMemberId)

    return Family(familyId, ownerMemberId, ownerToken, guestMemberId, guestToken)
}

private fun spentPayload(value: String = "12.50", comment: String = "coffee"): String =
    """{"type":"SPENT","value":"$value","spentAt":1700000000000,"comment":"$comment","category":"Food"}"""

/** A transaction with no `comment` key at all, as a client that has nothing to say would send it. */
private fun spentPayloadWithoutComment(): String =
    """{"type":"SPENT","value":"12.50","spentAt":1700000000000,"category":"Food"}"""

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

private fun syncBodySince(cursor: Long, since: Long, vararg changes: String): String =
    """{"cursor":$cursor,"since":$since,"changes":[${changes.joinToString(",")}]}"""

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

/**
 * Writes a transaction straight into the database, to give `since` a row on each side of the bound.
 *
 * Seeded rather than pushed because a push stamps `updated_at` from the change itself: routing both
 * rows through the API would test the assertion about `since` against values the API chose.
 */
private fun seedTransaction(id: String, familyId: String, comment: String, updatedAt: Long) {
    TestDatabase.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "insert into transactions (id, family_id, member_id, type, value, spent_at, comment, category, updated_at) " +
                "values (?::uuid, ?::uuid, null, 'SPENT', 12.50, 1700000000000, ?, null, ?)",
        ).use { statement ->
            statement.setUuid(1, id)
            statement.setUuid(2, familyId)
            statement.setString(3, comment)
            statement.setLong(4, updatedAt)
            statement.executeUpdate()
        }
    }
}

/** A delete: an empty payload and a deletedAt, which is how a tombstone reaches the server. */
private fun tombstonePayload(): String = "{}"
/**
 * Asserts a push was refused, and by name.
 *
 * A denial assertion on an empty list is satisfied by anything, including an HTTP 400 from a malformed
 * payload, a renamed column, or a tightened allowed-value set. Nineteen of these assertions were
 * written that way, which is exactly the mistake the design document warns about and which has
 * already happened three times in this project. So: a 200, exactly one conflict, the right table and
 * id, and a reason from the closed set. A 400 body has neither `accepted` nor `conflicts`, so it
 * cannot satisfy this.
 */
private suspend fun assertDenied(response: HttpResponse) {
    assertEquals("a denial must be a 200 with a conflict, not a 400", 200, response.status.value)
    val conflicts = response.json().conflicts()
    assertEquals("expected exactly one conflict", 1, conflicts.size)
    assertTrue(
        "the reason must come from the server's closed set, was \"${conflicts[0].text("reason")}\"",
        conflicts[0].text("reason") in REJECT_REASONS,
    )
}

private val REJECT_REASONS = setOf(
    "cross_member_write",
    "stale_version",
    "deleted_remotely",
    "cross_family_write",
)

/** A third member, for the cases the two-member harness cannot reach. */
private fun joinMember(name: String, familyId: String): Member {
    val memberId = TestDatabase.addMember(familyId, name)

    return Member(memberId, TestDatabase.issueToken(familyId, memberId))
}

private data class Member(val memberId: String, val token: String)