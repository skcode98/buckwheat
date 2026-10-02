package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.di.FakeSessionStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val staleRoster = listOf(
    FamilyMember(id = "old-1", displayName = "PreviousFamily", isOwner = true, joinedAt = "2026-09-01T00:00:00Z"),
    FamilyMember(id = "old-2", displayName = "AlsoPrevious", isOwner = false, joinedAt = "2026-09-01T00:00:01Z"),
)

private val defaultRoster = listOf(
    FamilyMember(
        id = "member-1",
        displayName = "Ada",
        isOwner = true,
        joinedAt = "2026-01-01T00:00:00Z",
    ),
    FamilyMember(
        id = "member-2",
        displayName = "Grace",
        isOwner = false,
        joinedAt = "2026-02-02T00:00:00Z",
    ),
)

private class FakeFamilyApi(
    val credentials: FamilyCredentials = FamilyCredentials("family-1", "member-1", "token-1"),
    val me: WhoAmI = WhoAmI("member-1", "family-1", "Suraj"),
    val invite: MintedInvite = MintedInvite("CODE", "2026-10-01T00:00:00Z"),
    val roster: List<FamilyMember> = defaultRoster,
) : FamilyApi {
    var createCalls: Int = 0
    var joinCalls: Int = 0
    var membersFailure: Throwable? = null

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

    override suspend fun members(token: String): List<FamilyMember> {
        membersFailure?.let { throw it }
        return roster
    }
}

private class FakeMembersCache(initial: List<FamilyMember> = emptyList()) : FamilyMembersCache {
    private val state = MutableStateFlow(initial)

    var cleared = false

    override fun members(): Flow<List<FamilyMember>> = state

    override suspend fun readMembers(): List<FamilyMember> = state.value

    override suspend fun replaceMembers(members: List<FamilyMember>) {
        state.value = members
    }

    override suspend fun clear() {
        cleared = true
        state.value = emptyList()
    }
}

class FamilySyncRegistrarTest {

    private val api = FakeFamilyApi()
    private val store = FakeSessionStore()
    private val cache = FakeMembersCache()
    private val registrar = FamilySyncRegistrar(store, FamilyApiFactory { api }, cache)

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
    fun aSuccessfulRefreshReplacesTheCachedRoster() = runTest {
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")

        val members = registrar.members()

        assertEquals(defaultRoster, members)
        assertEquals(defaultRoster, cache.readMembers())
    }

    @Test
    fun aFailedRefreshKeepsThePreviouslyCachedRoster() = runTest {
        val stale = listOf(
            FamilyMember(
                id = "member-old",
                displayName = "Grace",
                isOwner = false,
                joinedAt = "2025-12-01T00:00:00Z",
            )
        )
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")
        cache.replaceMembers(stale)
        api.membersFailure = IOException("family HTTP 503 service_unavailable")

        val members = registrar.members()

        assertEquals(stale, members)
        assertEquals(stale, cache.readMembers())
        assertFalse(cache.cleared)
    }

    @Test
    fun theRosterIsNullWithoutASession() = runTest {
        assertNull(registrar.members())
    }

    @Test
    fun enrolmentDiscardsARosterLeftBehindByAnEarlierFamily() = runTest {
        cache.replaceMembers(staleRoster)

        registrar.enrol("https://sync.example.com", "Suraj")

        assertTrue(cache.cleared)
        // The new family's roster, and none of the previous family's names. Asserting "empty" used to
        // pass for the wrong reason once enrolment started refetching, and preloading the same list the
        // fake API returns meant a leaked name and a fetched one were indistinguishable.
        val members = cache.readMembers()
        assertEquals(defaultRoster.map { it.displayName }.sorted(), members.map { it.displayName }.sorted())
        assertTrue(members.none { it.displayName.contains("Previous") })
    }

    @Test
    fun joiningDiscardsARosterLeftBehindByAnEarlierFamily() = runTest {
        store.save("https://sync.example.com", "token-old", "family-old", "member-1")
        cache.replaceMembers(staleRoster)

        registrar.join("https://sync.example.com", "CODE", "Suraj")

        assertTrue(cache.cleared)
        val members = cache.readMembers()
        assertEquals(defaultRoster.map { it.displayName }.sorted(), members.map { it.displayName }.sorted())
        assertTrue(members.none { it.displayName.contains("Previous") })
    }

    /**
 * A refresh that fails must fall back to the roster of *this* family, never an earlier one.
 *
 * The failure is set AFTER the join on purpose: joining now refetches the roster, so the cache holds
 * the new family's names by the time the refresh is made to fail. What this proves is that the
 * fallback path returns the current family's cache rather than anything left over — which is only
 * observable because the stale and current rosters are different lists.
 */
@Test
    fun aFailedRefreshAfterJoiningCannotSurfaceThePreviousFamily() = runTest {
        store.save("https://sync.example.com", "token-old", "family-old", "member-1")
        cache.replaceMembers(staleRoster)

        registrar.join("https://sync.example.com", "CODE", "Suraj")
        api.membersFailure = IOException("family HTTP 503 service_unavailable")

        val members = registrar.members()

        assertEquals(defaultRoster.map { it.displayName }.sorted(), members.orEmpty().map { it.displayName }.sorted())
        assertTrue(members.orEmpty().none { it.displayName.contains("Previous") })
    }

/**
     * Joining with the server unreachable must leave an empty roster, never the previous family's.
     *
     * What this pins is narrow and worth being precise about: that the roster is *empty* when the
     * refetch fails. It does NOT pin the clear-then-fetch ordering. A refactor that fetched first
     * would leave the cache unwritten, so this test would still pass with nothing ever written to
     * leak. The ordering is pinned by `joiningDiscardsARosterLeftBehindByAnEarlierFamily`, whose fetch
     * succeeds and would end with an empty cache under that refactor. Do not weaken that one on the
     * strength of this one existing.
     *
     * What this does catch is a fallback that restored the pre-join roster: those names would still be
     * sitting there, shown under a new family's budget with nothing on screen to say so.
     */
    @Test
    fun joiningWithTheServerUnreachableLeavesNoRosterAtAll() = runTest {
        store.save("https://sync.example.com", "token-old", "family-old", "member-1")
        cache.replaceMembers(staleRoster)
        api.membersFailure = IOException("family HTTP 503 service_unavailable")

        registrar.join("https://sync.example.com", "CODE", "Suraj")

        assertTrue("the cache must be cleared even when the refetch fails", cache.cleared)
        val members = cache.readMembers()
        assertTrue("a previous family's names must never be shown under a new family", members.isEmpty())
    }

    @Test
    fun signOutClearsTheStoredCredentialsAndTheRoster() = runTest {
        store.save("https://sync.example.com", "token-1", "family-1", "member-1")
        cache.replaceMembers(defaultRoster)

        registrar.signOut()

        assertTrue(store.cleared)
        assertNull(store.token())
        assertTrue(cache.cleared)
        assertEquals(emptyList<FamilyMember>(), cache.readMembers())
    }
}
