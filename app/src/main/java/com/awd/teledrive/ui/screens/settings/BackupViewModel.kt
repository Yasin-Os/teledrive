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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _lastBackupTime = MutableStateFlow(secureSettings.getLong("last_backup_time", 0L))
    val lastBackupTime = _lastBackupTime.asStateFlow()

    private val _backupFolders = MutableStateFlow(secureSettings.getStringList("backup_folders"))
    val backupFolders = _backupFolders.asStateFlow()

    private val _feedback = MutableSharedFlow<BackupFeedback>()
    val feedback: SharedFlow<BackupFeedback> = _feedback.asSharedFlow()

    // ডিফল্টভাবে Wi-Fi Only বন্ধ রাখা হলো যাতে মোবাইল ডাটা (এমবি) ও ওয়াইফাই উভয় ক্ষেত্রেই অটো-সিঙ্ক চলে
    val isBackupWifiOnly = settingsRepository.isBackupWifiOnlyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val backupWorkInfo = workerManager.getBackupWorkInfo()

    init {
        if (_backupFolders.value.isNotEmpty()) {
            settingsRepository.setAutoBackupEnabled(true)
            settingsRepository.setBackupWifiOnlyEnabled(false)
            workerManager.scheduleBackup(true)
            driveRepository.runImmediateFolderBackup()
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsRepository.setAutoBackupEnabled(enabled)
        if (enabled) {
            settingsRepository.setBackupWifiOnlyEnabled(false)
        }
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

        if (current.isNotEmpty()) {
            settingsRepository.setAutoBackupEnabled(true)
            settingsRepository.setBackupWifiOnlyEnabled(false)
            workerManager.scheduleBackup(true)
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

            settingsRepository.setAutoBackupEnabled(true)
            settingsRepository.setBackupWifiOnlyEnabled(false)
            workerManager.scheduleBackup(true)
            driveRepository.runImmediateFolderBackup()
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
        driveRepository.runImmediateFolderBackup()
        workerManager.triggerOneTimeBackup()
        _lastBackupTime.value = System.currentTimeMillis()
    }
}
