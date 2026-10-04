package family.sync

import family.sync.family.SecuritySettings
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FamilyBodyTest {

    @BeforeTest
    fun cleanDatabase() {
        TestDatabase.truncateAll()
    }

    @Test
    fun joinRejectsAMalformedBodyWithBadRequest() = runServer {
        val response = postJson("/v1/family/join", "{\"code\":")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("body_invalid", response.error())
    }

    @Test
    fun joinRejectsABodyThatIsNotAJsonObject() = runServer {
        listOf("[]", "\"nope\"", "42", "true", "null").forEach { body ->
            val response = postJson("/v1/family/join", body)

            assertEquals(HttpStatusCode.BadRequest, response.status, "accepted body: $body")
            assertEquals("body_invalid", response.error(), "accepted body: $body")
        }
    }

    @Test
    fun joinRejectsAnEmptyBodyWithBadRequest() = runServer {
        val response = postJson("/v1/family/join", "")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("body_invalid", response.error())
    }

    @Test
    fun createRejectsAMalformedBodyWithBadRequest() = runServer {
        val response = postJson("/v1/family/create", "not json at all")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("body_invalid", response.error())
    }

    @Test
    fun createRejectsABodyThatIsNotAJsonObject() = runServer {
        val response = postJson("/v1/family/create", "[]")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("body_invalid", response.error())
    }

    @Test
    fun joinRejectsAMissingCodeField() = runServer {
        val response = postJson("/v1/family/join", """{"displayName":"child"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("code_required", response.error())
    }

    @Test
    fun joinRejectsABlankCodeField() = runServer {
        val response = postJson("/v1/family/join", """{"code":"   ","displayName":"child"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("code_required", response.error())
    }

    @Test
    fun joinRejectsANonStringCodeField() = runServer {
        val response = postJson("/v1/family/join", """{"code":123,"displayName":"child"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("code_required", response.error())
    }

    @Test
    fun joinRejectsAMissingDisplayNameField() = runServer {
        val response = postJson("/v1/family/join", """{"code":"NOPE1234"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("displayName_required", response.error())
    }

    @Test
    fun aRejectedBodyNeverConsumesTheInvite() = runServer {
        val owner = createFamilyWithCode("parent")
        val code = owner.field("joinCode")

        val rejected = postJson("/v1/family/join", """{"code":""")
        assertEquals(HttpStatusCode.BadRequest, rejected.status)

        val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(owner.field("familyId"), joined.field("familyId"))
    }

    @Test
    fun aRejectedBodyCreatesNoFamily() = runServer {
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/family/create", "[]").status)

        assertEquals(0, TestDatabase.countRows("families"))
    }

    @Test
    fun aBodyOverTheCapIsRefusedAsTooLarge() = runServerWith(SecuritySettings(maxRequestBytes = 64)) {
        val oversized = """{"displayName":"${"x".repeat(512)}"}"""

        val response = postJson("/v1/family/create", oversized)

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.error())
        assertEquals(0, TestDatabase.countRows("families"))
    }

    @Test
    fun aBodyInsideTheCapIsStillAccepted() = runServerWith(SecuritySettings(maxRequestBytes = 64)) {
        val response = postJson("/v1/family/create", """{"displayName":"tiny"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, TestDatabase.countRows("families"))
    }

    @Test
    fun anOversizedJoinBodyIsRefusedBeforeItCanClaimAnInvite() = runServerWith(
        SecuritySettings(maxRequestBytes = 64),
    ) {
        val owner = createFamilyWithCode("parent")
        val code = owner.field("joinCode")

        val response = postJson(
            "/v1/family/join",
            """{"code":"$code","displayName":"${"x".repeat(512)}"}""",
        )

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.error())

        val afterwards = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")

        assertEquals(HttpStatusCode.OK, afterwards.status)
    }

    @Test
    fun anAbsurdlyLongDisplayNameIsRefused() = runServer {
        val response = postJson("/v1/family/create", """{"displayName":"${"x".repeat(101)}"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("displayName_too_long", response.error())
        assertEquals(0, TestDatabase.countRows("families"))
    }

    @Test
    fun aDisplayNameAtTheLimitIsAccepted() = runServer {
        val name = "x".repeat(100)

        val response = postJson("/v1/family/create", """{"displayName":"$name"}""")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun anAbsurdlyLongInviteCodeIsRefused() = runServer {
        val response = postJson("/v1/family/join", """{"code":"${"A".repeat(17)}","displayName":"a"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("code_too_long", response.error())
    }

    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitiveText()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createFamilyWithCode(
        displayName: String,
    ): JsonObject = postJson("/v1/family/create", """{"displayName":"$displayName"}""").json()
}
