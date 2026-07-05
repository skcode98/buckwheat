package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecurringRepository @Inject constructor(
    private val recurringDao: RecurringDao,
) {
    fun getAll(): List<RecurringTemplate> = recurringDao.getAll()

    fun getDueOnDay(day: Int): List<RecurringTemplate> = recurringDao.getDueOnDay(day)

    fun add(amount: BigDecimal, comment: String, dayOfMonth: Int) {
        recurringDao.insert(
            RecurringTemplate(
                amount = amount,
                comment = comment,
                dayOfMonth = dayOfMonth,
            )
        )
    }

    fun update(template: RecurringTemplate) {
        recurringDao.update(template)
    }

    fun delete(id: Int) {
        recurringDao.deleteById(id)
    }
}
