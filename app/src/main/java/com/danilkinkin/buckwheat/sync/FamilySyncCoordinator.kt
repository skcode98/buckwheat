package com.danilkinkin.buckwheat.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface SyncClock {
    fun now(): Long
}

@Singleton
class FamilySyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registrar: FamilySyncRegistrar,
    private val database: SyncDatabase,
    private val clock: SyncClock,
) {
    suspend fun enrol(baseUrl: String, displayName: String): FamilySession {
        val session = registrar.enrol(baseUrl, displayName)
        activate(session)
        return session
    }

    suspend fun join(baseUrl: String, code: String, displayName: String): FamilySession {
        val session = registrar.join(baseUrl, code, displayName)
        activate(session)
        return session
    }

    suspend fun signOut() {
        registrar.signOut()
        database.reset()
        SyncScheduler.cancel(context)
    }

    suspend fun whoami(): WhoAmI? = registrar.whoami()

    suspend fun syncNow() = SyncScheduler.syncNow(context)

    suspend fun members(): List<FamilyMember>? = registrar.members()

    /** Leaves the family then clears every local trace; true when the server leave succeeded. */
    suspend fun leave(): Boolean {
        val left = registrar.leave()
        if (left) {
            database.reset()
            SyncScheduler.cancel(context)
        }
        return left
    }

    private suspend fun activate(session: FamilySession) {
        database.enrolAll(
            memberId = session.memberId,
            familyId = session.familyId,
            enrolledAt = clock.now(),
        )
        SyncScheduler.schedule(context)
        SyncScheduler.syncNow(context)
    }
}
