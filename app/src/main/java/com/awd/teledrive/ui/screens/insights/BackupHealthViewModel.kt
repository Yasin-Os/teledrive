package com.awd.teledrive.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.awd.teledrive.data.repository.BackupHealthSnapshot
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupHealthViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
    private val workerManager: TeleDriveWorkerManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _snapshot = MutableStateFlow<BackupHealthSnapshot?>(null)
    val snapshot: StateFlow<BackupHealthSnapshot?> = _snapshot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRetrying = MutableStateFlow(false)
    val isRetrying: StateFlow<Boolean> = _isRetrying.asStateFlow()

    val isAutoBackupEnabled = settingsRepository.isAutoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isBackupWifiOnly = settingsRepository.isBackupWifiOnlyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Next run of the hourly backup worker, or null when nothing is scheduled. */
    val nextBackupTime: StateFlow<Long?> = workerManager.getBackupWorkInfo()
        .map { infos ->
            val info = infos.firstOrNull()
            if (info == null || info.state == WorkInfo.State.CANCELLED) {
                null
            } else {
                val next = info.nextScheduleTimeMillis
                if (next > 0L) next else null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refresh()
    }

    fun refresh() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _snapshot.value = driveRepository.getBackupHealthSnapshot()
            _isLoading.value = false
        }
    }

    fun retryFailed(key: String) {
        if (_isRetrying.value) return
        viewModelScope.launch {
            _isRetrying.value = true
            driveRepository.retryFailedBackup(key)
            _isRetrying.value = false
            refresh()
        }
    }

    fun retryAllFailed() {
        if (_isRetrying.value) return
        viewModelScope.launch {
            _isRetrying.value = true
            driveRepository.retryAllFailedBackups()
            _isRetrying.value = false
            refresh()
        }
    }

    /** Manual "Backup Now": runs the immediate backup and re-enqueues the worker, like the Settings button. */
    fun triggerManualBackup() {
        workerManager.triggerOneTimeBackup()
        refresh()
    }
}
