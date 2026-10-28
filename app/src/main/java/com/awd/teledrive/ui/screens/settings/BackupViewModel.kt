// File: app/src/main/java/com/awd/teledrive/ui/screens/settings/BackupViewModel.kt
package com.awd.teledrive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.secure.SecureSettings
import com.awd.teledrive.data.worker.TeleDriveWorkerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupFeedback {
    object NoFolders : BackupFeedback()
    object Starting : BackupFeedback()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val workerManager: TeleDriveWorkerManager
) : ViewModel() {

    val isAutoBackupEnabled = settingsRepository.isAutoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _lastBackupTime = MutableStateFlow(secureSettings.getLong("last_backup_time", 0L))
    val lastBackupTime = _lastBackupTime.asStateFlow()

    private val _backupFolders = MutableStateFlow(secureSettings.getStringList("backup_folders"))
    val backupFolders = _backupFolders.asStateFlow()

    private val _feedback = MutableSharedFlow<BackupFeedback>()
    val feedback: SharedFlow<BackupFeedback> = _feedback.asSharedFlow()

    val isBackupWifiOnly = settingsRepository.isBackupWifiOnlyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val backupWorkInfo = workerManager.getBackupWorkInfo()

    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsRepository.setAutoBackupEnabled(enabled)
        workerManager.scheduleBackup(enabled)
        if (enabled && _backupFolders.value.isNotEmpty()) {
            driveRepository.runImmediateFolderBackup()
            workerManager.triggerOneTimeBackup()
        }
    }

    fun setBackupWifiOnly(enabled: Boolean) {
        settingsRepository.setBackupWifiOnlyEnabled(enabled)
        if (isAutoBackupEnabled.value) {
            workerManager.scheduleBackup(true)
        }
    }

    fun toggleBackupFolder(path: String) {
        val normalized = driveRepository.normalizeFolderPath(path)
        val current = _backupFolders.value.toMutableList()
        if (current.contains(normalized) || current.contains(path)) {
            current.remove(normalized)
            current.remove(path)
        } else {
            current.add(normalized)
        }
        
        _backupFolders.value = current
        secureSettings.saveStringList("backup_folders", current)

        if (isAutoBackupEnabled.value && current.isNotEmpty()) {
            driveRepository.runImmediateFolderBackup()
        }
    }

    fun addCustomBackupFolder(path: String) {
        if (path.isEmpty()) return
        val normalized = driveRepository.normalizeFolderPath(path)
        val current = _backupFolders.value.toMutableList()
        if (!current.contains(normalized)) {
            current.add(normalized)
            _backupFolders.value = current
            secureSettings.saveStringList("backup_folders", current)
            if (isAutoBackupEnabled.value) {
                driveRepository.runImmediateFolderBackup()
            }
        }
    }

    fun triggerManualBackup() {
        if (_backupFolders.value.isEmpty()) {
            viewModelScope.launch {
                _feedback.emit(BackupFeedback.NoFolders)
            }
            return
        }
        viewModelScope.launch {
            _feedback.emit(BackupFeedback.Starting)
        }
        // সাথে সাথে ইনস্ট্যান্ট ব্যাকআপ এবং ব্যাকগ্রাউন্ড ওয়ার্কার দুটিই একসাথে কাজ শুরু করবে
        driveRepository.runImmediateFolderBackup()
        workerManager.triggerOneTimeBackup()
        _lastBackupTime.value = System.currentTimeMillis()
    }
}
