package com.danilkinkin.buckwheat.family

import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The sibling of [SpendsRepository]'s period read-outs that the family screens can depend on without
 * taking the whole repository: [SpendsRepository] is `final`, so it cannot be mocked in a family test,
 * and it carries far more than these two streams anyway.
 */
interface FamilyPeriodSource {
    fun getStartPeriodDate(): Flow<Date>

    fun getFinishPeriodDate(): Flow<Date?>
}

@Singleton
class SpendsFamilyPeriodSource @Inject constructor(
    private val spendsRepository: SpendsRepository,
) : FamilyPeriodSource {
    override fun getStartPeriodDate(): Flow<Date> = spendsRepository.getStartPeriodDate()

    override fun getFinishPeriodDate(): Flow<Date?> = spendsRepository.getFinishPeriodDate()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FamilyPeriodSourceModule {
    @Binds
    @Singleton
    abstract fun bindFamilyPeriodSource(impl: SpendsFamilyPeriodSource): FamilyPeriodSource
}