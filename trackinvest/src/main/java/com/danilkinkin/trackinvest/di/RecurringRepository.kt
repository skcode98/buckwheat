/*
 * Copyright 2026, skcode98, All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import androidx.lifecycle.LiveData
import com.danilkinkin.trackinvest.data.dao.InvestmentDao
import com.danilkinkin.trackinvest.data.dao.RecurringSipDao
import com.danilkinkin.trackinvest.data.dao.TemplateDao
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import com.danilkinkin.trackinvest.data.entities.Template
import com.danilkinkin.trackinvest.data.processDueSip
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecurringRepository @Inject constructor(
    private val recurringSipDao: RecurringSipDao,
    private val templateDao: TemplateDao,
    private val investmentDao: InvestmentDao,
) {
    fun sips(): LiveData<List<RecurringSip>> = recurringSipDao.getAll()

    fun templates(): LiveData<List<Template>> = templateDao.getAll()

    suspend fun saveSip(sip: RecurringSip) {
        if (sip.uid == 0) {
            recurringSipDao.insert(sip)
        } else {
            recurringSipDao.update(sip)
        }
    }

    suspend fun deleteSip(sip: RecurringSip) {
        recurringSipDao.deleteById(sip.uid)
    }

    suspend fun saveTemplate(template: Template) {
        if (template.uid == 0) {
            templateDao.insert(template)
        } else {
            templateDao.update(template)
        }
    }

    suspend fun deleteTemplate(template: Template) {
        templateDao.deleteById(template.uid)
    }

    suspend fun quickLog(template: Template) {
        investmentDao.insert(
            Investment(
                date = System.currentTimeMillis(),
                type = template.type,
                amount = template.amount,
                note = template.note,
                tags = template.tags,
                account = template.account,
            ),
        )
    }

    suspend fun processDueSips(): Int {
        val due = recurringSipDao.getAllNow().filter { it.isActive }
        if (due.isEmpty()) return 0
        val zoneId = ZoneId.systemDefault()
        val today = System.currentTimeMillis()
        var count = 0
        due.forEach { sip ->
            val result = processDueSip(sip, today, zoneId)
            if (result.investments.isNotEmpty()) {
                investmentDao.insertAll(result.investments)
                recurringSipDao.update(sip.copy(nextRun = result.nextRun).also { it.uid = sip.uid })
                count += result.investments.size
            }
        }
        return count
    }
}
