package com.danilkinkin.buckwheat.family

import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.sync.FamilyMember
import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MEMBER_ID = "me"
private val DAY = Date(1_700_000_000_000L)

private fun session(memberId: String) = FamilySession(
    baseUrl = "http://localhost",
    token = "token",
    familyId = "family",
    memberId = memberId,
    joinCode = "code",
)

private fun spent(id: String, memberId: String, value: String, date: Date) =
    FamilyTransaction(
        id = id,
        type = TransactionType.SPENT,
        value = value.toBigDecimal(),
        date = date,
        memberId = memberId,
    )

class FakeSessionStore(
    private val initial: FamilySession?,
    private val roster: List<FamilyMember> = listOf(
        FamilyMember(MEMBER_ID, "Ren", departed = false, joinedAt = "2024-11-15"),
    ),
) : FamilySessionStore {
    override fun session(): Flow<FamilySession?> = flowOf(initial)

    override suspend fun current(): FamilySession? = initial

    override suspend fun token(): String? = initial?.token

    override suspend fun baseUrl(): String? = initial?.baseUrl

    override suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String) = Unit

    override suspend fun setBaseUrl(baseUrl: String) = Unit

    override suspend fun clear() = Unit

    override fun members(): Flow<List<FamilyMember>> = flowOf(roster)
}

class FakeSpendsRepository(
    private val start: Date,
    private val finish: Date,
) : FamilyPeriodSource {
    override fun getStartPeriodDate(): Flow<Date> = flowOf(start)

    override fun getFinishPeriodDate(): Flow<Date?> = flowOf(finish)
}

class FakeFamilyTransactionDao(
    private val rows: List<FamilyTransaction> = emptyList(),
) : FamilyTransactionDao {
    override fun getAllInPeriod(startDate: Date, endDate: Date): List<FamilyTransaction> =
        rows.filter { it.date.time >= startDate.time && it.date.time <= endDate.time }

    override fun getAllNow(): List<FamilyTransaction> = rows

    override fun getById(id: String): FamilyTransaction? = rows.firstOrNull { it.id == id }

    override suspend fun upsertOne(
        id: String,
        type: TransactionType,
        value: BigDecimal,
        date: Date,
        comment: String,
        category: String?,
        memberId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) = Unit

    override suspend fun deleteById(id: String): Int = 0

    override suspend fun updateCategory(id: String, category: String?, version: Int, updatedAt: Long) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun updateMemberId(id: String, memberId: String) = Unit

    override suspend fun deleteRowsWhereMemberDiffersFrom(memberId: String): Int = 0

    override suspend fun attributeNullMembersTo(memberId: String): Int = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theFamilyTotalIsTheSumOfEveryMemberSpend() = runTest {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                spent("a", memberId = "me", value = "10.00", date = DAY),
                spent("b", memberId = "me", value = "5.00", date = DAY),
                spent("c", memberId = "them", value = "20.00", date = DAY),
            ),
        )
        val viewModel = FamilyViewModel(dao, sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals(BigDecimal("35.00"), viewModel.familyTotal.value)
        assertEquals(BigDecimal("15.00"), viewModel.ownSpend.value)
    }

    @Test
    fun onlyRowsInsideTheCurrentPeriodCount() = runTest {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                spent("a", memberId = "me", value = "10.00", date = DAY),
                spent("b", memberId = "them", value = "99.00", date = Date(DAY.time - 86_400_000L)),
            ),
        )
        val viewModel = FamilyViewModel(dao, sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals(BigDecimal("10.00"), viewModel.familyTotal.value)
    }

    @Test
    fun namesComeFromTheRosterNotTheRawMemberId() = runTest {
        val viewModel = FamilyViewModel(FakeFamilyTransactionDao(), sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals("Ren", viewModel.members.value.single { it.id == MEMBER_ID }.displayName)
    }
}