package family.sync

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val names = members.json()["members"]?.jsonArrayText()
        assertEquals(listOf("parent", "child"), names)
    }

    @Test
    fun anEmptyDisplayNameIsRefused() = runServer {
        val response = postJson("/v1/family/create", """{"displayName":""}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createFamily(
        displayName: String,
    ): HttpResponse = postJson("/v1/family/create", """{"displayName":"$displayName"}""")

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.mintInvite(
        token: String,
    ): String = postJson("/v1/family/invite", "{}", token).field("code")
}
