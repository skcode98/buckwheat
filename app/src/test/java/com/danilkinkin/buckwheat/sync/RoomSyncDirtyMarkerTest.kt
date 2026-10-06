package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

/**
 * Against a real in-memory Room database. The invariants here are properties of the schema rather than
 * of the Kotlin: `pending_mutations` is keyed by (table_name, record_id), so marking twice updates one
 * row rather than adding a second, and that is exactly what makes the version carried in
 * [SettledChange] the only thing that can tell two edits apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSyncDirtyMarkerTest {

    private lateinit var db: DatabaseModule
    private lateinit var pending: PendingMutationDao
    private lateinit var marker: RoomSyncDirtyMarker
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DatabaseModule::class.java,
        ).allowMainThreadQueries().build()
        pending = db.pendingMutationDao()
        marker = RoomSyncDirtyMarker(
            stampDao = db.syncStampDao(),
            pendingMutationDao = pending,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun anUpsertQueuesTheRowAndAdvancesItsVersion() = runTest {
        db.transactionDao().insert(spend("tag-1"))

        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        assertEquals(1, pending.isQueued(SyncTables.TRANSACTIONS, "tag-1"))
        assertEquals(2, db.transactionDao().getAllNow().single().version)
    }

    @Test
    fun markingTheSameRowTwiceKeepsOneQueueEntry() = runTest {
        db.transactionDao().insert(spend("tag-1"))

        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")
        now += 50
        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        assertEquals(1, pending.count())
        assertEquals(3, db.transactionDao().getAllNow().single().version)
    }

    @Test
    fun theQueueEntryMovesToTheLatestEditRatherThanAccumulating() = runTest {
        // This is the in-flight edit from the other side: the queue row is overwritten in place, so the
        // push that is already in flight is the only record that the earlier edit existed.
        db.transactionDao().insert(spend("tag-1"))
        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        now += 50
        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        val queued = pending.getAllNow().single()
        assertEquals(1_050L, queued.queuedAt)
        assertEquals(3, db.transactionDao().getAllNow().single().version)
    }

    @Test
    fun aDeleteOfARowThatNeverReachedAFamilyIsNotQueued() = runTest {
        db.transactionDao().insert(spend("tag-1"))
        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        marker.markDelete(SyncTables.TRANSACTIONS, "tag-1", familyId = null, syncSeq = 0L)

        assertEquals(0, pending.isQueued(SyncTables.TRANSACTIONS, "tag-1"))
    }

    @Test
    fun aDeleteOfARowThatReachedAFamilyIsQueuedAsATombstone() = runTest {
        marker.markDelete(SyncTables.TRANSACTIONS, "tag-1", familyId = "family-1", syncSeq = 5L)

        val queued = pending.getAllNow().single()
        assertEquals(SyncTables.TRANSACTIONS, queued.table)
        assertEquals("tag-1", queued.recordId)
        assertTrue(queued.isDelete)
    }

    @Test
    fun aDeleteAfterAnUpsertFlipsTheExistingEntryRatherThanAddingOne() = runTest {
        db.transactionDao().insert(spend("tag-1"))
        marker.markUpsert(SyncTables.TRANSACTIONS, "tag-1")

        now += 50
        marker.markDelete(SyncTables.TRANSACTIONS, "tag-1", familyId = "family-1", syncSeq = 5L)

        assertEquals(1, pending.count())
        assertTrue(pending.getAllNow().single().isDelete)
    }

    @Test
    fun releasingTheFamilyClearsMembershipButKeepsTheRows() = runTest {
        db.transactionDao().insert(spend("t-1", familyId = "family-1", memberId = "member-1"))

        marker.releaseFamily()

        val row = db.transactionDao().getAllNow().single()
        assertEquals(null, row.familyId)
        assertEquals(null, row.memberId)
        assertEquals(0L, row.syncSeq)
        assertEquals(1, row.version)
    }

    @Test
    fun markUpsertsMarksEveryListedRow() = runTest {
        db.transactionDao().insert(spend("tag-1"))
        db.transactionDao().insert(spend("tag-2"))

        marker.markUpserts(SyncTables.TRANSACTIONS, listOf("tag-1", "tag-2"))

        assertEquals(2, pending.count())
    }

    private fun spend(
        id: String,
        familyId: String? = null,
        memberId: String? = null,
    ) = Transaction(
        id = id,
        type = TransactionType.SPENT,
        value = BigDecimal.TEN,
        date = Date(0),
        comment = "coffee",
        category = null,
        familyId = familyId,
        memberId = memberId,
    )
}