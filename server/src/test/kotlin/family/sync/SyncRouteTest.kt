package family.sync

import family.sync.db.setUuid
import family.sync.sync.MAX_PULL_ROWS
import family.sync.sync.MAX_TEXT_LENGTH
import family.sync.sync.FAMILY_GOVERNED_TABLES
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
import java.io.File
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
    fun anOwnerMaySetThePool() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("family_state", id, 1, 1000L, poolPayload())),
            family.ownerToken,
        )

        assertEquals(listOf("family_state:$id"), response.json().acceptedKeys())
    }

    @Test
    fun aMemberMayNotSetThePool() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("family_state", id, 1, 1000L, poolPayload())),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun aMemberMayNotReallocateSomebodyElsesSlice() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("period_limits", id, 1, 1000L, limitPayload("30000.00", family.ownerMemberId))),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun anOwnerMayRecordAHouseholdExpense() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, householdPayload())),
            family.ownerToken,
        )

        assertEquals(listOf("transactions:$id"), response.json().acceptedKeys())
    }

    @Test
    fun aMemberMayNotRecordAHouseholdExpense() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 1, 1000L, householdPayload())),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun aMemberMayNotRewriteAHouseholdExpenseAsAPersonalOne() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, householdPayload())), family.ownerToken)

        // The downgrade: same row, the head's household spend overwritten as an ordinary personal one.
        // Without the stored-bucket rule this succeeds and quietly moves the rent out of the shared tier.
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, spentPayload("2500.00", "rent"))),
            family.guestToken,
        )

        assertDenied(response)
    }

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

    @Test
    fun theHeadMayRaiseARequestForSomebodyElse() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 1, 1000L, requestPayload(family.ownerMemberId, family.guestMemberId)),
            ),
            family.ownerToken,
        )

        assertEquals(listOf("spend_assignments:$id"), response.json().acceptedKeys())
    }

    @Test
    fun aMemberMayNotRaiseARequest() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 1, 1000L, requestPayload(family.guestMemberId, family.ownerMemberId)),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun theHeadMayNotRaiseARequestAimedAtThemselves() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        // Otherwise the consent step is decorative: the head charges themselves through the one route
        // that never asks anybody.
        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 1, 1000L, requestPayload(family.ownerMemberId, family.ownerMemberId)),
            ),
            family.ownerToken,
        )

        assertDenied(response)
    }

    @Test
    fun aMemberMayNotForgeAnAnsweredRequestAimedAtThemselves() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        // The important one. A fresh id, already ACCEPTED, target themselves: accept this and a member
        // can charge their own budget for any amount without anybody being asked.
        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    1,
                    1000L,
                    answeredRequestPayload(family.guestMemberId, family.guestMemberId),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun theTargetMayAnswerTheirOwnRequest() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId),
                ),
            ),
            family.guestToken,
        )

        assertEquals(listOf("spend_assignments:$id"), response.json().acceptedKeys())
    }

    @Test
    fun nobodyElseMayAnswerARequest() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)

        // Even the head. If the head could answer, the consent step would mean nothing.
        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId),
                ),
            ),
            family.ownerToken,
        )

        assertDenied(response)
    }

    @Test
    fun theTargetMayNotAimTheirOwnRequestSomewhereElse() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)

        // The attack: the head raised this at the guest, and the guest now re-aims it at the head. If
        // the incoming target were trusted the row would become the head's to answer, and the guest
        // would have redirected the request without the head ever seeing it. Two earlier versions of
        // this test re-sent the byte-identical payload and so exercised nothing at all while reading as
        // though they covered this.
        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    requestPayload(family.ownerMemberId, family.ownerMemberId),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)

        // And the row is untouched: still the guest's request, still unanswered.
        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val stored = pull.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals(family.guestMemberId, stored.text("targetMemberId"))
        assertEquals("PENDING", stored.text("status"))
    }

    @Test
    fun theTargetMayNotRestateTheAmountWhileAnswering() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)

        // Consenting to 250 and having 2500 written is not consent.
        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId, "2500.00"),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

    @Test
    fun anAnsweredRequestCannotBeAnsweredAgain() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)
        postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId),
                ),
            ),
            family.guestToken,
        )

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    3,
                    3000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId, "5000.00"),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

    /**
 * A member may not delete the head's pool.
     *
     * The severe one. `storedFacts` did not list `family_state` or `period_limits`, so every write to
     * them read as "no such row", and an early return for a tombstone with no stored row turned that
     * into permission. A member could not *set* the pool and could wipe it.
     */@Test
    fun aMemberMayNotDeleteThePoolOrTheSplit() = runServer {
        val family = newFamily()
        val poolId = UUID.randomUUID().toString()
        val limitId = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("family_state", poolId, 1, 1000L, poolPayload())), family.ownerToken)
        postJson(
            "/v1/sync",
            syncBody(0, change("period_limits", limitId, 1, 1000L, limitPayload("13000.00", family.guestMemberId))),
            family.ownerToken,
        )

        listOf(poolId to "family_state", limitId to "period_limits").forEach { (id, table) ->
            val response = postJson(
                "/v1/sync",
                syncBody(0, change(table, id, 2, 2000L, tombstonePayload(), deletedAt = 2000L)),
                family.guestToken,
            )
            assertDenied(response)
        }

        // And the head still sees their own pool afterwards.
        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val tables = pull.json().recordKeys()
        assertTrue("the pool was deleted by a member", tables.contains("family_state:$poolId"))
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
     * Every governed table is looked up by `storedFacts`, or a rule reading it sees nothing.
     *
     * The failure this guards is not hypothetical: `family_state` and `period_limits` were missing from
     * that lookup, which made a member's delete of the head's pool look like a delete of a row that did
     * not exist, and therefore permitted.
     */
    @Test
    fun everyGovernedTableIsLookedUp() {
        val source = File("src/main/kotlin/family/sync/sync/SyncStore.kt").readText()
        val lookup = source.substringAfter("private fun storedFacts(").substringBefore("return connection.prepareStatement")

        FAMILY_GOVERNED_TABLES.forEach {
            assertTrue("$it is governed but storedFacts does not look it up", lookup.contains("\"$it\""))
        }
    }

/**
     * A member may not delete a household expense they did not record.
     *
     * The delete-side counterpart to `aMemberMayNotRecordAHouseholdExpense`. A tombstone carries no
     * payload, so the bucket can only come from the stored row, and without that lookup the rule was
     * bypassable by deleting the row rather than writing it.
     */
    @Test
    fun aMemberMayNotDeleteAHouseholdExpense() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, householdPayload())), family.ownerToken)

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("transactions", id, 2, 2000L, tombstonePayload(), deletedAt = 2000L)),
            family.guestToken,
        )

        assertDenied(response)
    }

    /**
     * A member may not erase the request they already answered.
     *
     * The answer is a consent record: the head raised it, the member agreed, and the row is kept
     * precisely so that agreement can be seen afterwards. Letting the member delete it afterwards makes
     * the audit trail whatever they choose to keep.
     */
    @Test
    fun aMemberMayNotDeleteARequestTheyAlreadyAnswered() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)
        postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 2, 2000L, answeredRequestPayload(family.ownerMemberId, family.guestMemberId)),
            ),
            family.guestToken,
        )

        val response = postJson(
            "/v1/sync",
            syncBody(0, change("spend_assignments", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
            family.guestToken,
        )

        assertDenied(response)
    }

    /**
     * A push against a pending request has to actually answer it.
     *
     * `status` is a closed set, but membership in that set is not the same as moving off PENDING.
     * Without requiring a real answer, the target could push their row straight back to PENDING and
     * leave the request pending for ever -- a state the UI would never produce, and so not one the app
     * can be trusted to avoid producing.
     */
    @Test
    fun aTargetMayNotPushTheirOwnRequestBackToPending() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    2,
                    2000L,
                    requestPayload(family.ownerMemberId, family.guestMemberId),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

/**
     * A departed member's ledger is not up for adoption.
     *
     * `member_id` is `on delete set null`, so when somebody leaves the family their rows stay behind with
     * no owner, and `write` rebinds member_id to whoever pushes next. The cross-member guard therefore
     * has to treat an ownerless row as belonging to nobody rather than to anyone -- which is what the
     * `!= null` clause it replaced got wrong, in the same shape as the `storedFacts` omission that let a
     * member delete the pool.
*/
    @Test
    fun aDepartedMembersSpendsAreNotClaimable() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        // The author has to be the one who leaves, or the row still has an owner and the ordinary
        // cross-member rule refuses it for that reason instead -- the test would pass even if the
        // ownerless case were wide open.
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload("20.00", "theirs"))), family.ownerToken)

        // The remaining member joins first, because only the owner can mint an invite and the owner is
        // about to leave.
        val survivor = joinMember("Survivor", family.ownerToken)

        // Now the author leaves. `member_id` is `on delete set null`, so the row survives with no owner.
        postJson("/v1/family/leave", "{}", family.ownerToken)

        // And nobody may take it over: not the value, and not the row itself.
        assertDenied(
            postJson(
                "/v1/sync",
                syncBody(0, change("transactions", id, 2, 2000L, spentPayload("9000.00", "mine now"))),
                survivor.guestToken,
            )
        )
        assertDenied(
            postJson(
                "/v1/sync",
                syncBody(0, change("transactions", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
                survivor.guestToken,
            )
        )

        // The amount is untouched.
        val pull = postJson("/v1/sync", syncBody(0), survivor.guestToken)
        assertEquals("20.00", pull.json().recordAt(0)["payload"]!!.jsonObject.text("value"))
    }

    /**
     * Nobody may delete a request that has been answered -- the target who agreed, nor the head who
     * asked.
     *
     * The head is always the creator, so permitting the creator to delete an answered request permitted
     * the head, and the consent record was one-sided. The row outlives the answer precisely so that what
     * was agreed can be looked at afterwards, by both sides.
     */
    @Test
    fun nobodyMayDeleteARequestThatHasBeenAnswered() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        raiseRequest(family, id)
        postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 2, 2000L, answeredRequestPayload(family.ownerMemberId, family.guestMemberId)),
            ),
            family.guestToken,
        )

        assertDenied(
            postJson(
                "/v1/sync",
                syncBody(0, change("spend_assignments", id, 3, 3000L, tombstonePayload(), deletedAt = 3000L)),
                family.ownerToken,
            )
        )
        assertDenied(
            postJson(
                "/v1/sync",
                syncBody(0, change("spend_assignments", id, 4, 4000L, tombstonePayload(), deletedAt = 4000L)),
                family.guestToken,
            )
        )
    }

/**
     * The owner can actually allocate a slice, and it comes back.
     *
     * Every other `period_limits` test here is a denial, and a suite made only of denials cannot show
     * that a table works at all -- it can only show that it is refused. This is the test that would
     * have caught the original bug, where the client sent a period key the server rejected outright: the
     * symptom was a table that was refused everywhere, which is indistinguishable from a table that
     * was never written.
     */
    @Test
    fun anOwnerMayAllocateASliceAndItRoundTrips() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val push = postJson(
            "/v1/sync",
            syncBody(0, change("period_limits", id, 1, 1000L, limitPayload("13000.00", family.guestMemberId))),
            family.ownerToken,
        )
        assertEquals(listOf("period_limits:$id"), push.json().acceptedKeys())

        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        assertTrue(pull.json().recordKeys().contains("period_limits:$id"))
        val payload = pull.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("13000.00", payload.text("limitValue"))
        assertEquals(family.guestMemberId, payload.text("memberId"))
    }

    /**
     * The pool itself round-trips too, for the same reason.
     */
    @Test
    fun thePoolRoundTrips() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val push = postJson("/v1/sync", syncBody(0, change("family_state", id, 1, 1000L, poolPayload())), family.ownerToken)
        assertEquals(listOf("family_state:$id"), push.json().acceptedKeys())

        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val payload = pull.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("30000.00", payload.text("budget"))
        assertEquals("9000.00", payload.text("householdTier"))
        assertEquals("EQUAL", payload.text("commonSplitRule"))
    }

    /**
     * A request round-trips, and so does the answer.
     *
     * The consent flow is the one thing in this feature where a silent failure is worst: a request
     * that never arrives means the target is never asked, and the spend is never recorded against
     * them. Denials are tested elsewhere; this proves the happy path writes.
     */
    @Test
    fun aRequestAndItsAnswerRoundTrip() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        assertEquals(
            listOf("spend_assignments:$id"),
            postJson(
                "/v1/sync",
                syncBody(0, change("spend_assignments", id, 1, 1000L, requestPayload(family.ownerMemberId, family.guestMemberId))),
                family.ownerToken,
            ).json().acceptedKeys(),
        )

        val answered = postJson(
            "/v1/sync",
            syncBody(0, change("spend_assignments", id, 2, 2000L, answeredRequestPayload(family.ownerMemberId, family.guestMemberId))),
            family.guestToken,
        )
        assertEquals(listOf("spend_assignments:$id"), answered.json().acceptedKeys())

        val pull = postJson("/v1/sync", syncBody(0), family.ownerToken)
        val payload = pull.json().recordAt(0)["payload"]!!.jsonObject
        assertEquals("ACCEPTED", payload.text("status"))
        assertEquals("250.00", payload.text("amount"))
        assertEquals(family.guestMemberId, payload.text("targetMemberId"))
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
        val later = joinMember("Later", family.ownerToken)

        postJson("/v1/family/leave", "{}", family.ownerToken)

        val members = postJson("/v1/family/members", "{}", family.guestToken)
            .json()["members"]!!.jsonArray.map { it.jsonObject }

        val promoted = members.first { it["id"]!!.jsonPrimitive.content == family.guestMemberId }
        assertTrue("the earliest survivor was not promoted", promoted["isOwner"]!!.jsonPrimitive.content == "true")

        val other = members.first { it["id"]!!.jsonPrimitive.content == later.guestMemberId }
        assertTrue("a second head appeared", other["isOwner"]!!.jsonPrimitive.content != "true")
    }

    /**
     * The point of the promotion: the survivor can actually change the budget afterwards. Without it
     * this push is refused `owner_only` for ever and the family is stuck.
     */
@Test
    fun theSurvivorCanActuallySetThePoolAfterwards() = runServer {
        val family = newFamily()
        joinMember("Second", family.ownerToken)
        postJson("/v1/family/leave", "{}", family.ownerToken)

        // The guest, not "Second": `newFamily` already made two members and the earliest survivor is
        // promoted. Using the later joiner's token asserted that a *non*-owner could set the pool, which
        // is a different test and correctly fails.
        val id = UUID.randomUUID().toString()
        val response = postJson(
            "/v1/sync",
            syncBody(0, change("family_state", id, 1, 1000L, poolPayload())),
            family.guestToken,
        )

        assertEquals(listOf("family_state:$id"), response.json().acceptedKeys())
    }

    @Test
    fun anOrdinaryMemberLeavingDoesNotChangeWhoTheHeadIs() = runServer {
        val family = newFamily()
        val second = joinMember("Second", family.ownerToken)

        postJson("/v1/family/leave", "{}", second.guestToken)

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

    private suspend fun ApplicationTestBuilder.raiseRequest(family: Family, id: String) {
        postJson(
            "/v1/sync",
            syncBody(
                0,
                change("spend_assignments", id, 1, 1000L, requestPayload(family.ownerMemberId, family.guestMemberId)),
            ),
            family.ownerToken,
        )
    }

    /**
     * Every family-governed table has a branch in `SyncStore.authorize`, and nothing else does.
     *
     * Default-allow is what lets a normal member save a tag or close a period, so it cannot be the
     * guard: a table added to the contract and forgotten here would inherit permission silently. This
     * is that guard, and it exists because a table list maintained by hand is exactly the thing that
     * drifts.
     */
    @Test
    fun aGovernedTableHasARule() {
        val governed = FAMILY_GOVERNED_TABLES

        assertTrue("family_state is not governed", "family_state" in governed)
        assertTrue("period_limits is not governed", "period_limits" in governed)
        assertTrue("spend_assignments is not governed", "spend_assignments" in governed)
        assertTrue("transactions is not governed", "transactions" in governed)
        assertTrue("archived_transactions is not governed", "archived_transactions" in governed)

        // Personal tables must stay out of it. Putting one in would make its rules apply to it.
        listOf("budget_periods", "saved_categories", "saved_tags", "recurring_templates", "savings_goals")
            .forEach { assertTrue("$it should not be family-governed", it !in governed) }

        // And every governed table must actually exist in the contract, or the branch can never run.
        val contractTables = SyncTables.ALL.map { spec -> spec.name }
        governed.forEach {
            assertTrue("$it is governed but is not in SyncTables.ALL", it in contractTables)
        }

        // Both directions, which the earlier version of this test missed. Checking only that the set is
        // a subset of the contract would pass even if a `when` branch were added to authorize() without
        // a matching entry here -- which is exactly the drift this test exists to catch.
        assertEquals(
            "FAMILY_GOVERNED_TABLES must match the tables authorize() actually branches on, in both " +
                "directions. Adding a governed table without listing it here means its rule never runs.",
            setOf(
                "family_state",
                "period_limits",
                "spend_assignments",
                "transactions",
                "archived_transactions",
            ),
            governed,
        )

        // And it must match what authorize() *reads*, not just the literal above. An earlier version of
        // this test compared the set against itself and so passed even if a branch were deleted from
        // authorize() -- which is the drift its own comment claimed to catch.
        val authorize = File("src/main/kotlin/family/sync/sync/SyncStore.kt")
            .readText()
            .substringAfter("private fun authorize(")
            .substringBefore("private data class StoredFacts")
        governed.forEach {
            assertTrue("authorize() has no branch for the governed table $it", authorize.contains("\"$it\""))
        }
    }

    /**
     * `status` is a closed set, for the same reason `bucket` is.
     *
     * `alreadyResolved` is decided by `status != "PENDING"`, so a free-text status lets the head raise
     * a request already marked ACCEPTED, which the target can then never answer. Griefing rather than
     * theft, but it is the same "send an unexpected value" pattern the bucket rule exists to stop.
     */
    @Test
    fun aRequestStatusIsOneOfTheThreeItIsAllowedToBe() {
        listOf("transactions", "archived_transactions").forEach { table ->
            assertTrue(
                "$table.bucket must stay optional so an older client can still sync",
                SyncTables.require(table).columns.first { column -> column.column == "bucket" }.nullable,
            )
        }

        val status = SyncTables.require("spend_assignments").columns
            .first { column -> column.column == "status" }
        assertEquals(setOf("PENDING", "ACCEPTED", "REJECTED"), status.allowedValues)
    }

    /**
     * `bucket` carries a closed value set, and it is the one that decides who may spend from the shared
     * tier.
     *
     * Asserted here rather than left to a probe, because an unwired `allowedValues` compiles, passes
     * every other test, and silently permits any string -- which reads as neither MEMBER nor HOUSEHOLD
     * and therefore matches no rule.
     */
    @Test
    fun aBucketIsOneOfTheTwoItIsAllowedToBe() {
        listOf("transactions", "archived_transactions").forEach { table ->
            val bucket = SyncTables.require(table).columns.first { column -> column.column == "bucket" }
            assertEquals(setOf("MEMBER", "HOUSEHOLD"), bucket.allowedValues)
        }
    }

    /**
     * A member cannot re-bucket their own personal spend into the household tier.
     *
     * The other direction -- a member downgrading the head's household rent -- is covered by
     * `aMemberMayNotRewriteAHouseholdExpenseAsAPersonalOne`. Both directions were needed: an earlier
     * version compared only the *stored* bucket, which closed the downgrade and quietly opened the
     * upgrade.
     */
    @Test
    fun aMemberMayNotPromoteAPersonalSpendIntoTheHouseholdTier() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()
        postJson("/v1/sync", syncBody(0, change("transactions", id, 1, 1000L, spentPayload())), family.guestToken)

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "transactions",
                    id,
                    2,
                    2000L,
                    spentPayload("2500.00", "rent").replaceFirst("{", """{"bucket":"HOUSEHOLD","""),
                ),
            ),
            family.guestToken,
        )

        assertDenied(response)
    }

    /**
     * The head cannot raise a request that is already answered.
     *
     * Not about the money being taken -- it is the target's own budget, and the target can see it --
     * but about consent. A request created as ACCEPTED is one the target is then permanently locked out
     * of answering, so the ask is skipped while looking exactly like one that was made. Restricting
     * `status` to three legal values did not close this; it only made the payload tidier on its way.
     */
    @Test
    fun theHeadMayNotRaiseARequestThatIsAlreadyAnswered() = runServer {
        val family = newFamily()
        val id = UUID.randomUUID().toString()

        val response = postJson(
            "/v1/sync",
            syncBody(
                0,
                change(
                    "spend_assignments",
                    id,
                    1,
                    1000L,
                    answeredRequestPayload(family.ownerMemberId, family.guestMemberId),
                ),
            ),
            family.ownerToken,
        )

        assertDenied(response)
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

private fun poolPayload(): String =
    """{"budget":"30000.00","householdTier":"9000.00","startDate":0,"finishDate":30000,""" +
        """"currency":"INR","householdDetailVisibleToAll":false,"commonSplitRule":"EQUAL",""" +
        """"tagsVisibleToSelf":true,"familyAiEnabled":true}"""

private fun limitPayload(value: String, memberId: String): String =
    """{"periodId":"$PERIOD_UUID","memberId":"$memberId","limitValue":"$value"}"""

/** A household expense, as the head records one. */
private fun householdPayload(): String =
    """{"type":"SPENT","value":"2500.00","spentAt":1700000000000,"comment":"rent",""" +
        """"category":"Home","bucket":"HOUSEHOLD"}"""

/** A request awaiting an answer. */
private fun requestPayload(creator: String, target: String): String =
    """{"periodId":"$PERIOD_UUID","targetMemberId":"$target","createdByMemberId":"$creator",""" +
        """"amount":"250.00","category":null,"comment":"school","date":1700000000000,""" +
        """"status":"PENDING","resolvedAt":null}"""

/** The same request after an answer, with the amount defaulted to the one that was asked for. */
private fun answeredRequestPayload(
    creator: String,
    target: String,
    amount: String = "250.00",
): String =
    """{"periodId":"$PERIOD_UUID","targetMemberId":"$target","createdByMemberId":"$creator",""" +
        """"amount":"$amount","category":null,"comment":"school","date":1700000000000,""" +
        """"status":"ACCEPTED","resolvedAt":1700000000000}"""

/**
 * A canonical uuid standing in for the client's derived period key.
 *
 * `period_id` is validated as a UUID. The Android client derives this key from the period's start date
 * because `budget_periods` rows only exist for closed periods, and it shipped as a readable string
 * that this server rejected outright -- so the pool and every request silently refused to sync and
 * nothing anywhere said so. Using a real uuid here is what makes these tests reach `authorize` at all.
 */
private val PERIOD_UUID: String = java.util.UUID.nameUUIDFromBytes("pool:0".toByteArray(Charsets.UTF_8)).toString()
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
    "owner_only",
    "cross_member_write",
    "stale_version",
    "deleted_remotely",
    "cross_family_write",
)
/** A third member, for the cases the two-member harness cannot reach. */
private suspend fun ApplicationTestBuilder.joinMember(name: String, ownerToken: String): Family {
    val code = postJson("/v1/family/invite", "{}", ownerToken).field("code")
    val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"$name"}""")
    return Family(
        familyId = "",
        ownerMemberId = "",
        ownerToken = ownerToken,
        guestMemberId = joined.field("memberId"),
        guestToken = joined.field("token"),
    )
}