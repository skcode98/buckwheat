package family.sync

import family.sync.family.RateLimitSetting
import family.sync.family.SecuritySettings
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun runServerWith(
    settings: SecuritySettings,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { familySyncModule(TestDatabase.dataSource, settings) }
    block()
}

class InviteRedeemTest {

    @BeforeTest
    fun cleanDatabase() {
        TestDatabase.truncateAll()
    }

    @Test
    fun theFirstDeviceCreatesAFamilyAndBecomesItsOwner() = runServer {
        val response = postJson("/v1/family/create", """{"displayName":"parent"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.field("familyId").isNotBlank())
        assertTrue(response.field("memberId").isNotBlank())
        assertTrue(response.field("token").isNotBlank())
        assertTrue(TestDatabase.isOwner(response.field("memberId")))
    }

    @Test
    fun theOwnerMintsAnInviteAndAnotherDeviceRedeemsIt() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))

        val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(owner.field("familyId"), joined.field("familyId"))
        assertTrue(joined.field("memberId") != owner.field("memberId"))
        assertTrue(joined.field("token").isNotBlank())
    }

    @Test
    fun aRedeemedMemberGetsItsOwnWorkingToken() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        val whoAmI = postJson("/v1/family/whoami", "{}", joined.field("token"))

        assertEquals(HttpStatusCode.OK, whoAmI.status)
        assertEquals(joined.field("memberId"), whoAmI.field("memberId"))
        assertEquals(owner.field("familyId"), whoAmI.field("familyId"))
        assertEquals("child", whoAmI.field("displayName"))
    }

    @Test
    fun anInviteCodeIsSingleUse() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        assertEquals(
            HttpStatusCode.OK,
            postJson("/v1/family/join", """{"code":"$code","displayName":"first"}""").status,
        )

        val second = postJson("/v1/family/join", """{"code":"$code","displayName":"second"}""")

        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("invite_already_used", second.json()["error"]?.jsonPrimitiveText())
    }

    @Test
    fun anExpiredInviteCodeIsRefusedDistinctlyFromAUsedOne() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        TestDatabase.expireInvite(code)

        val response = postJson("/v1/family/join", """{"code":"$code","displayName":"late"}""")

        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("invite_expired", response.json()["error"]?.jsonPrimitiveText())
    }

    @Test
    fun anUnknownInviteCodeIsRefused() = runServer {
        val response = postJson("/v1/family/join", """{"code":"NOPE1234","displayName":"stranger"}""")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("invite_not_found", response.json()["error"]?.jsonPrimitiveText())
    }

    @Test
    fun anUnauthenticatedDeviceCannotMintAnInvite() = runServer {
        val response = postJson("/v1/family/invite", "{}", null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun aTamperedTokenCannotMintAnInvite() = runServer {
        val owner = createFamily("parent")

        val response = postJson("/v1/family/invite", "{}", tamper(owner.field("token")))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun aNonOwnerCannotMintAnInvite() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        val child = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        val response = postJson("/v1/family/invite", "{}", child.field("token"))

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("owner_only", response.json()["error"]?.jsonPrimitiveText())
    }

    @Test
    fun aMemberCanSeeTheFamilyMemberList() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        val members = postJson("/v1/family/members", "{}", owner.field("token"))

        assertEquals(HttpStatusCode.OK, members.status)
        val names = members.json()["members"]?.jsonArrayField("displayName")
        assertEquals(listOf("parent", "child"), names)
    }

    /**
     * The id is what makes attribution possible at all: a client holding a synced transaction knows
     * only the `member_id` that wrote it, and a conflict names only `wonByMemberId`. A roster of bare
     * names cannot answer either question, so the id has to be on the wire.
     */
    @Test
    fun theMemberListCarriesTheIdAndOwnerFlag() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        val child = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        val members = postJson("/v1/family/members", "{}", owner.field("token")).json()["members"]

        val ids = members?.jsonArrayField("id")
        assertEquals(2, ids?.size)
        assertEquals(owner.field("memberId"), ids?.first())
        assertEquals(child.field("memberId"), ids?.last())
        assertEquals(listOf("true", "false"), members?.jsonArrayField("isOwner"))
    }

    @Test
    fun anEmptyDisplayNameIsRefused() = runServer {
        val response = postJson("/v1/family/create", """{"displayName":""}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun leavingRevokesTheTokenAndRemovesTheMembership() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        val child = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        val left = postJson("/v1/family/leave", "{}", child.field("token"))

        assertEquals(HttpStatusCode.OK, left.status)
        assertEquals("true", left.json()["left"]?.jsonPrimitiveText())
        val afterwards = postJson("/v1/family/whoami", "{}", child.field("token"))
        assertEquals(HttpStatusCode.Unauthorized, afterwards.status)
        assertEquals(1, TestDatabase.countRows("members"))
        assertEquals(1, TestDatabase.readTokenHashes().size)
    }

    @Test
    fun theOwnerKeepsItsTokenAfterSomebodyElseLeaves() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        val child = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")
        postJson("/v1/family/leave", "{}", child.field("token"))

        val whoAmI = postJson("/v1/family/whoami", "{}", owner.field("token"))

        assertEquals(HttpStatusCode.OK, whoAmI.status)
        assertEquals("parent", whoAmI.field("displayName"))
    }

    @Test
    fun anUnauthenticatedDeviceCannotLeave() = runServer {
        val response = postJson("/v1/family/leave", "{}", null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun anExpiredTokenCannotEvenLeave() = runServerWith(SecuritySettings(tokenLifetime = Duration.ZERO)) {
        val owner = createFamily("parent")

        val response = postJson("/v1/family/leave", "{}", owner.field("token"))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun creatingFamiliesIsRateLimitedPerCaller() {
        val clock = FakeClock()
        val settings = SecuritySettings(
            createRate = RateLimitSetting(2, Duration.ofMinutes(10)),
            clock = clock::now,
        )

        runServerWith(settings) {
            assertEquals(HttpStatusCode.OK, createFamily("one").status)
            assertEquals(HttpStatusCode.OK, createFamily("two").status)

            val blocked = createFamily("three")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("rate_limited", blocked.json()["error"]?.jsonPrimitiveText())
            assertEquals(2, TestDatabase.countRows("families"))

            clock.advance(Duration.ofMinutes(10))

            assertEquals(HttpStatusCode.OK, createFamily("four").status)
        }
    }

    @Test
    fun joiningIsRateLimitedPerCaller() {
        val clock = FakeClock()
        val settings = SecuritySettings(
            joinRate = RateLimitSetting(1, Duration.ofMinutes(5)),
            clock = clock::now,
        )

        runServerWith(settings) {
            createFamily("parent")
            val first = postJson("/v1/family/join", """{"code":"AAAAAA1","displayName":"a"}""")
            assertEquals(HttpStatusCode.NotFound, first.status)

            val blocked = postJson("/v1/family/join", """{"code":"BBBBBB2","displayName":"b"}""")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("rate_limited", blocked.json()["error"]?.jsonPrimitiveText())

            clock.advance(Duration.ofMinutes(5))

            val afterTheWindow = postJson("/v1/family/join", """{"code":"BBBBBB2","displayName":"b"}""")
            assertEquals(HttpStatusCode.NotFound, afterTheWindow.status)
        }
    }

    @Test
    fun mintingInvitesIsRateLimitedPerCaller() {
        val clock = FakeClock()
        val settings = SecuritySettings(
            inviteRate = RateLimitSetting(1, Duration.ofMinutes(5)),
            clock = clock::now,
        )

        runServerWith(settings) {
            val owner = createFamily("parent")
            assertEquals(HttpStatusCode.OK, postJson("/v1/family/invite", "{}", owner.field("token")).status)

            val blocked = postJson("/v1/family/invite", "{}", owner.field("token"))
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("rate_limited", blocked.json()["error"]?.jsonPrimitiveText())

            clock.advance(Duration.ofMinutes(5))

            assertEquals(HttpStatusCode.OK, postJson("/v1/family/invite", "{}", owner.field("token")).status)
        }
    }

    @Test
    fun oneOwnerCannotFloodTheInviteTable() = runServerWith(
        SecuritySettings(maxOutstandingInvites = 2),
    ) {
        val owner = createFamily("parent")
        assertEquals(HttpStatusCode.OK, postJson("/v1/family/invite", "{}", owner.field("token")).status)
        assertEquals(HttpStatusCode.OK, postJson("/v1/family/invite", "{}", owner.field("token")).status)

        val blocked = postJson("/v1/family/invite", "{}", owner.field("token"))

        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertEquals("too_many_invites", blocked.json()["error"]?.jsonPrimitiveText())
        assertEquals(2, TestDatabase.countRows("invites"))
    }

    @Test
    fun mintingAnInvitePrunesInvitesNobodyCanRedeemAnymore() = runServer {
        val owner = createFamily("parent")
        mintInvite(owner.field("token"))
        TestDatabase.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("update invites set redeemed_at = now() - interval '30 days'")
            }
        }

        assertEquals(HttpStatusCode.OK, postJson("/v1/family/invite", "{}", owner.field("token")).status)

        assertEquals(1, TestDatabase.countRows("invites"))
    }

    @Test
    fun aUsedInviteNeverCreatesASecondMember() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        postJson("/v1/family/join", """{"code":"$code","displayName":"first"}""")

        repeat(3) {
            postJson("/v1/family/join", """{"code":"$code","displayName":"again"}""")
        }

        assertEquals(2, TestDatabase.countRows("members"))
    }

    @Test
    fun anExpiredInviteCreatesNoMember() = runServer {
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))
        TestDatabase.expireInvite(code)

        val response = postJson("/v1/family/join", """{"code":"$code","displayName":"late"}""")

        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals(1, TestDatabase.countRows("members"))
    }

    private suspend fun ApplicationTestBuilder.createFamily(
        displayName: String,
    ): HttpResponse = postJson("/v1/family/create", """{"displayName":"$displayName"}""")

    private suspend fun ApplicationTestBuilder.mintInvite(
        token: String,
    ): String = postJson("/v1/family/invite", "{}", token).field("code")
}

private class FakeClock(private var millis: Long = 0L) {
    fun now(): Long = millis

    fun advance(duration: Duration) {
        millis += duration.toMillis()
    }
}
