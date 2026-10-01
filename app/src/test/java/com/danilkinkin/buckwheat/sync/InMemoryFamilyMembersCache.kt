package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * In-memory [FamilyMembersCache] for unit tests.
 *
 * The real cache is DataStore-backed, and a DataStore write hops to `Dispatchers.IO`, which a
 * `runTest` scheduler cannot flush. Injecting the real one into a ViewModel test therefore silently
 * truncates whatever runs after it: a `signOut()` that also clears the roster never reaches its
 * `_messages.send(...)`, and the test fails on an empty channel with no indication that the disk was
 * involved. Holding the value in a `MutableStateFlow` keeps every call on the test dispatcher, which
 * is what `advanceUntilIdle()` is actually able to wait for.
 */
class InMemoryFamilyMembersCache(
    initial: List<FamilyMember> = emptyList(),
) : FamilyMembersCache {

    private val state = MutableStateFlow(initial)

    /** Mirrors the real store's contract: a read on a never-populated cache yields nothing. */
    var writes: Int = 0
        private set

    var clears: Int = 0
        private set

    override fun members(): Flow<List<FamilyMember>> = state

    override suspend fun readMembers(): List<FamilyMember> = members().first()

    override suspend fun replaceMembers(members: List<FamilyMember>) {
        writes++
        state.value = members
    }

    override suspend fun clear() {
        clears++
        state.value = emptyList()
    }
}