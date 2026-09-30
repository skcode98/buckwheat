package family.sync

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
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
        val owner = createFamily("parent")
        val code = mintInvite(owner.field("token"))

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

    private suspend fun HttpResponse.error(): String? = json()["error"]?.jsonPrimitiveText()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createFamily(
        displayName: String,
    ): HttpResponse = postJson("/v1/family/create", """{"displayName":"$displayName"}""")

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.mintInvite(
        token: String,
    ): String = postJson("/v1/family/invite", "{}", token).field("code")
}
