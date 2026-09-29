package com.danilkinkin.buckwheat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpFamilyApiTest {

    @Test
    fun theEndpointIsTheFamilyActionOnTheBaseUrl() {
        assertEquals("https://sync.example.com/v1/family/create", familyEndpoint("https://sync.example.com", "create"))
        assertEquals("https://sync.example.com/v1/family/create", familyEndpoint("https://sync.example.com/", "create"))
        assertEquals(
            "https://sync.example.com/v1/family/join",
            familyEndpoint("  https://sync.example.com/  ", "join"),
        )
    }

    @Test
    fun credentialsAreDecodedFromTheCreateAndJoinResponse() {
        val credentials = decodeCredentials(
            """{"familyId":"family-1","memberId":"member-1","token":"token-1"}"""
        )

        assertEquals("family-1", credentials.familyId)
        assertEquals("member-1", credentials.memberId)
        assertEquals("token-1", credentials.token)
    }

    @Test
    fun whoamiIsDecoded() {
        val who = decodeWhoAmI(
            """{"memberId":"member-1","familyId":"family-1","displayName":"Suraj"}"""
        )

        assertEquals("member-1", who.memberId)
        assertEquals("family-1", who.familyId)
        assertEquals("Suraj", who.displayName)
    }

    @Test
    fun anInviteIsDecoded() {
        val invite = decodeInvite(
            """{"code":"AB12CD","expiresAt":"2026-10-01T00:00:00Z"}"""
        )

        assertEquals("AB12CD", invite.code)
        assertEquals("2026-10-01T00:00:00Z", invite.expiresAt)
    }

    @Test
    fun aResponseThatIsNotJsonIsAnIoFailure() {
        val failure = runCatching { decodeCredentials("<html>nope</html>") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("family response was not json", failure?.message)
    }

    @Test
    fun aResponseMissingAFieldIsAFailure() {
        val failure = runCatching { decodeWhoAmI("""{"memberId":"member-1"}""") }.exceptionOrNull()

        assertTrue(failure is Exception)
    }

    @Test
    fun theServerErrorCodeIsSurfacedWhenPresent() {
        assertEquals("owner_only", errorCodeOf("""{"error":"owner_only"}"""))
        assertEquals(null, errorCodeOf("""{"other":"x"}"""))
        assertEquals(null, errorCodeOf("not json at all"))
        assertEquals(null, errorCodeOf(""))
    }
}
