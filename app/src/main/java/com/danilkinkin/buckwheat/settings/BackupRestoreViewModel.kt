package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import com.danilkinkin.buckwheat.di.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
) : ViewModel() {
    suspend fun exportBackup(): String = backupRepository.exportBackup()

    suspend fun restoreBackup(json: String): Boolean = backupRepository.restoreBackup(json)
}
