package com.danilkinkin.buckwheat.family

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.sync.FamilySession
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the device crash `IllegalStateException: Cannot access database on the main thread`
 * (Room `assertNotMainThread`, thrown from `FamilyViewModel$rows` whenever the family sheet opened).
 *
 * `FamilyTransactionDao.getAllInPeriod` is a SYNCHRONOUS DAO method — Room generates it with an
 * `assertNotMainThread()` guard and runs the query inline on the caller's thread. When the VM's
 * `rows` flow collected it from `viewModelScope` (Dispatchers.Main), that was the main thread.
 *
 * This test uses a REAL in-memory Room database (not a fake) so the generated DAO's main-thread
 * guard actually fires. `UnconfinedTestDispatcher` keeps the coroutines collected on the test
 * thread, which Room treats as the main thread under Robolectric: pre-fix the query runs on that
 * thread and throws; post-fix the `flowOn(Dispatchers.IO)` moves it off and this test passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyViewModelRoomRegressionTest {

    @Test
    fun familyTotalIsReadableFromARealRoomDatabase() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DatabaseModule::class.java).build()
        val dao: FamilyTransactionDao = db.familyTransactionDao()
        try {
            val seedDate = Date(1_700_000_000_000L)
            dao.insert(
                FamilyTransaction(
                    id = "t1",
                    type = TransactionType.SPENT,
                    value = BigDecimal("123.45"),
                    date = seedDate,
                    comment = "",
                    category = null,
                    memberId = "me",
                    syncSeq = 1L,
                    updatedAt = 0L,
                    deletedAt = null,
                    version = 1,
                ),
            )

            val vm = FamilyViewModel(
                familyTransactionDao = dao,
                sessionStore = FakeSessionStore(
                    FamilySession(
                        baseUrl = "http://localhost",
                        token = "token",
                        familyId = "family",
                        memberId = "me",
                        joinCode = "code",
                    ),
                ),
                spendsRepository = FakeSpendsRepository(
                    start = Date(seedDate.time - 86_400_000L),
                    finish = seedDate,
                ),
            )

            val total = vm.familyTotal.first { it == BigDecimal("123.45") }
            assertEquals(BigDecimal("123.45"), total)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}