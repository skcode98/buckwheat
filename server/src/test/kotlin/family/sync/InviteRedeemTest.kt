package family.sync

import family.sync.family.RateLimitSetting
import family.sync.family.SecuritySettings
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun creatingAFamilyReturnsAJoinCodeThatAnotherDeviceCanRedeem() = runServer {
        val credentials = postJson("/v1/family/create", """{"displayName":"Owner"}""").json()
        val code = credentials.field("joinCode")

        val guest = postJson("/v1/family/join", """{"code":"$code","displayName":"Guest"}""").json()

        assertNotNull(guest["token"])
    }

    @Test
    fun aRedeemedMemberGetsItsOwnWorkingToken() = runServer {
        val owner = createFamilyWithCode()
        val joined = postJson(
            "/v1/family/join",
            """{"code":"${owner.field("joinCode")}","displayName":"child"}""",
        )

        val whoAmI = postJson("/v1/family/whoami", "{}", joined.field("token"))

        assertEquals(HttpStatusCode.OK, whoAmI.status)
        assertEquals(joined.field("memberId"), whoAmI.field("memberId"))
        assertEquals(owner.field("familyId"), whoAmI.field("familyId"))
        assertEquals("child", whoAmI.field("displayName"))
    }

    @Test
    fun oneJoinCodeAdmitsEveryMemberOfTheFamily() = runServer {
        val owner = createFamilyWithCode()
        val code = owner.field("joinCode")

        val joins = listOf("first", "second", "third").map { name ->
            postJson("/v1/family/join", """{"code":"$code","displayName":"$name"}""")
        }

        assertEquals(List(3) { HttpStatusCode.OK }, joins.map { it.status })
        val credentials = joins.map { it.json() }
        val memberIds = credentials.map { it.field("memberId") }
        val tokens = credentials.map { it.field("token") }
        assertEquals(3, memberIds.toSet().size, "the three joins shared a member id")
        assertEquals(3, tokens.toSet().size, "the three joins shared a token")
        assertFalse(owner.field("memberId") in memberIds, "a join reused the owner's member id")
        credentials.forEach { credential ->
            val whoAmI = postJson("/v1/family/whoami", "{}", credential.field("token"))
            assertEquals(HttpStatusCode.OK, whoAmI.status)
            assertEquals(credential.field("memberId"), whoAmI.field("memberId"))
        }

        val roster = postJson("/v1/family/members", "{}", owner.field("token")).json()["members"]
        val ids = roster?.jsonArrayField("id").orEmpty()
        assertEquals(4, ids.size)
        assertTrue(owner.field("memberId") in ids)
        assertTrue(ids.containsAll(memberIds))
        assertEquals(List(4) { "false" }, roster?.jsonArrayField("departed"))
    }

    @Test
    fun anExpiredJoinCodeIsRefused() = runServer {
        val owner = createFamilyWithCode()
        val code = owner.field("joinCode")
        TestDatabase.expireInvite(code)

        val response = postJson("/v1/family/join", """{"code":"$code","displayName":"late"}""")

        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("invite_expired", response.json()["error"]?.jsonPrimitiveText())
        assertEquals(1, TestDatabase.countRows("members"))
    }

    @Test
    fun anUnknownInviteCodeIsRefused() = runServer {
        val response = postJson("/v1/family/join", """{"code":"NOPE1234","displayName":"stranger"}""")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("invite_not_found", response.json()["error"]?.jsonPrimitiveText())
    }

    @Test
    fun aMemberCanSeeTheFamilyMemberList() = runServer {
        val owner = createFamilyWithCode()
        postJson("/v1/family/join", """{"code":"${owner.field("joinCode")}","displayName":"child"}""")

        val members = postJson("/v1/family/members", "{}", owner.field("token"))

        assertEquals(HttpStatusCode.OK, members.status)
        val names = members.json()["members"]?.jsonArrayField("displayName")
        assertEquals(listOf("parent", "child"), names)
    }

    /**
     * The id is what makes attribution possible at all: a client holding a synced transaction knows
     * only the `member_id` that wrote it, and a conflict names only `wonByMemberId`. A roster of bare
     * names cannot answer either question, so the id has to be on the wire. `departed` starts false for
     * everyone, including the creator -- a member that has never left is not a departed member.
     */
    @Test
    fun theMemberListCarriesTheIdAndDepartedFlag() = runServer {
        val owner = createFamilyWithCode()
        val child = postJson(
            "/v1/family/join",
            """{"code":"${owner.field("joinCode")}","displayName":"child"}""",
        )

        val members = postJson("/v1/family/members", "{}", owner.field("token")).json()["members"]

        val ids = members?.jsonArrayField("id")
        assertEquals(2, ids?.size)
        assertEquals(owner.field("memberId"), ids?.first())
        assertEquals(child.field("memberId"), ids?.last())
        assertEquals(listOf("false", "false"), members?.jsonArrayField("departed"))
    }

    @Test
    fun anEmptyDisplayNameIsRefused() = runServer {
        val response = postJson("/v1/family/create", """{"displayName":""}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    /**
     * One leave, every consequence of it.
     *
     * These were two tests that both stood up a family, joined a second device and left, then each
     * asserted a different half of the same outcome: the row surviving with a stamp, and the token
     * being dead. Split that way a regression that dropped the row and kept the token still left one
     * green. Kept as one test, and it checks the roster too, because "the row survives" has to mean
     * the family can still see it.
     */
    @Test
    fun leavingKeepsTheMemberRowStampsItDepartedAndRevokesTheToken() = runServer {
        val owner = createFamilyWithCode()
        val child = postJson(
            "/v1/family/join",
            """{"code":"${owner.field("joinCode")}","displayName":"child"}""",
        )

        val left = postJson("/v1/family/leave", "{}", child.field("token"))

        assertEquals(HttpStatusCode.OK, left.status)
        assertEquals("true", left.json()["left"]?.jsonPrimitiveText())

        // The row is still there, which is the whole point: `transactions.member_id` points at it and
        // `on delete set null` would have stripped the author off every row the member ever wrote.
        assertEquals(2, TestDatabase.countRows("members"))
        assertTrue(
            TestDatabase.isDeparted(child.field("memberId")),
            "the departed member was not stamped",
        )
        assertFalse(
            TestDatabase.isDeparted(owner.field("memberId")),
            "the member that stayed was stamped as departed",
        )
        val roster = postJson("/v1/family/members", "{}", owner.field("token")).json()
        assertEquals(
            listOf("false", "true"),
            roster["members"]?.jsonArrayField("departed"),
        )

        // And the departed device is actually out.
        assertEquals(0, TestDatabase.countTokens(child.field("memberId")))
        assertEquals(
            HttpStatusCode.Unauthorized,
            postJson("/v1/family/whoami", "{}", child.field("token")).status,
        )
    }

    @Test
    fun theOwnerKeepsItsTokenAfterSomebodyElseLeaves() = runServer {
        val owner = createFamilyWithCode()
        val child = postJson(
            "/v1/family/join",
            """{"code":"${owner.field("joinCode")}","displayName":"child"}""",
        )
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
        val owner = createFamilyWithCode()

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
            createFamilyWithCode()
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
    fun thereIsNoInviteRoute() = runServer {
        val token = createFamilyWithCode().field("token")

        assertEquals(HttpStatusCode.NotFound, postJson("/v1/family/invite", "{}", token).status)
    }

    private suspend fun ApplicationTestBuilder.createFamilyWithCode(
        displayName: String = "parent",
    ): JsonObject = createFamily(displayName).json()

    private suspend fun ApplicationTestBuilder.createFamily(
        displayName: String,
    ): HttpResponse = postJson("/v1/family/create", """{"displayName":"$displayName"}""")
}

private class FakeClock(private var millis: Long = 0L) {
    fun now(): Long = millis

    fun advance(duration: Duration) {
        millis += duration.toMillis()
    }
}
