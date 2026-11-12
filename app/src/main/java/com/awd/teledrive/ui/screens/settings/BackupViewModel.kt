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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.isAutoBackupEnabled.value)

    private val _lastBackupTime = MutableStateFlow(secureSettings.getLong("last_backup_time", 0L))
    val lastBackupTime = _lastBackupTime.asStateFlow()

    private val _backupFolders = MutableStateFlow(secureSettings.getStringList("backup_folders"))
    val backupFolders = _backupFolders.asStateFlow()

    private val _feedback = MutableSharedFlow<BackupFeedback>()
    val feedback: SharedFlow<BackupFeedback> = _feedback.asSharedFlow()

    // প্রাথমিক মান সংরক্ষিত সেটিং থেকেই নেওয়া হয়, যাতে টগল
    // প্রথম মুহূর্তেই ভুল অবস্থা দেখিয়ে না যায়।
    val isBackupWifiOnly = settingsRepository.isBackupWifiOnlyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settingsRepository.isBackupWifiOnlyEnabled.value)

    val backupWorkInfo = workerManager.getBackupWorkInfo()

    init {
        if (_backupFolders.value.isNotEmpty()) {
            // সংরক্ষিত টগল সম্মান করা হয়: Auto Backup চালু থাকলেই
            // ওয়ার্কার (সঠিক নেটওয়ার্ক শর্তসহ) আবার বসানো হয়; বন্ধ
            // থাকলে পুরোনো ওয়ার্কার বাতিল করা হয়। আগের কোড এখানে
            // টগল জোর করে চালু আর Wi-Fi Only জোর করে বন্ধ করে দিত।
            workerManager.scheduleBackup(settingsRepository.isAutoBackupEnabled.value)
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsRepository.setAutoBackupEnabled(enabled)
        workerManager.scheduleBackup(enabled)
        if (enabled && _backupFolders.value.isNotEmpty()) {
            driveRepository.runImmediateFolderBackup()
        }
    }

    fun setBackupWifiOnly(enabled: Boolean) {
        settingsRepository.setBackupWifiOnlyEnabled(enabled)
        if (settingsRepository.isAutoBackupEnabled.value) {
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

        if (current.isNotEmpty() && settingsRepository.isAutoBackupEnabled.value) {
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

            if (settingsRepository.isAutoBackupEnabled.value) {
                workerManager.scheduleBackup(true)
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
        // "Last backup" must reflect when a backup actually finished, not
        // when it started: refresh it from the persisted value once the
        // immediate run completes.
        // ম্যানুয়াল ব্যাকআপ: ব্যবহারকারীর স্পষ্ট নির্দেশ, তাই Auto Backup
        // টগল ও Wi-Fi Only এখানে প্রযোজ্য নয়।
        driveRepository.runImmediateFolderBackup(manual = true) {
            _lastBackupTime.value = secureSettings.getLong("last_backup_time", 0L)
        }
        workerManager.triggerOneTimeBackup()
    }
}
