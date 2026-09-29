package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.di.FakeSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFamilyApi(
    val credentials: FamilyCredentials = FamilyCredentials("family-1", "member-1", "token-1"),
    val me: WhoAmI = WhoAmI("member-1", "family-1", "Suraj"),
    val invite: MintedInvite = MintedInvite("CODE", "2026-10-01T00:00:00Z"),
) : FamilyApi {
    var createCalls: Int = 0
    var joinCalls: Int = 0

    override suspend fun createFamily(displayName: String): FamilyCredentials {
        createCalls += 1
        return credentials
    }

    override suspend fun joinFamily(code: String, displayName: String): FamilyCredentials {
        joinCalls += 1
        return credentials
    }

    override suspend fun whoami(token: String): WhoAmI = me

    override suspend fun mintInvite(token: String): MintedInvite = invite
}

class FamilySyncRegistrarTest {

    private val api = FakeFamilyApi()
    private val store = FakeSessionStore()
    private val registrar = FamilySyncRegistrar(store, FamilyApiFactory { api })

    @Test
    fun enrolCreatesTheFamilyAndStoresTheCredentials() = runTest {
        val session = registrar.enrol("https://sync.example.com/", "Suraj")

        assertEquals("https://sync.example.com", session.baseUrl)
        assertEquals("token-1", session.token)
        assertEquals("family-1", session.familyId)
        assertEquals("member-1", session.memberId)
        assertEquals(1, api.createCalls)
        assertEquals(session, store.saved.single())
    }

    @Test
    fun joiningRedeemsTheInviteAndStoresTheCredentials() = runTest {
        val session = registrar.join("  https://sync.example.com  ", "CODE", "Suraj")

        assertEquals("https://sync.example.com", session.baseUrl)
        assertEquals(1, api.joinCalls)
        assertEquals(0, api.createCalls)
        assertEquals("token-1", store.token())
    }

    @Test
    fun whoamiUsesTheStoredSession() = runTest {
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")

        val me = registrar.whoami()

        assertEquals("Suraj", me?.displayName)
    }

    @Test
    fun inviteUsesTheStoredSession() = runTest {
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")

        val minted = registrar.invite()

        assertEquals("CODE", minted?.code)
    }

    @Test
    fun whoamiAndInviteAreNullWithoutASession() = runTest {
        assertNull(registrar.whoami())
        assertNull(registrar.invite())
    }

    @Test
    fun signOutClearsTheStoredCredentials() = runTest {
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")

        registrar.signOut()

        assertTrue(store.cleared)
        assertNull(store.token())
    }
}
