package com.awd.teledrive.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TeleDriveWorkerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository
) {
    fun scheduleBackup(enabled: Boolean) {
        try {
            if (!enabled) {
                WorkManager.getInstance(context).cancelUniqueWork("media_backup_worker")
                return
            }

            val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
            val data = Data.Builder()
                .putLong("last_backup_time", lastBackupTime)
                .build()

            // Wi-Fi Only চালু থাকলে শুধু unmetered (সাধারণত Wi-Fi)
            // নেটওয়ার্কে ব্যাকআপ চলবে; বন্ধ থাকলে যেকোনো সংযোগে চলবে।
            val requiredNetworkType = if (settingsRepository.isBackupWifiOnlyEnabled.value) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }

            val backupRequest = PeriodicWorkRequestBuilder<MediaBackupWorker>(1, TimeUnit.HOURS)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(requiredNetworkType)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "media_backup_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                backupRequest
            )

            // ব্যাকআপ চালু করলে সাথে সাথে একবার ফোল্ডার স্ক্যান ও আপলোড শুরু করবে
            driveRepository.runImmediateFolderBackup()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerOneTimeBackup() {
        try {
            // ওয়ার্কারের জন্য অপেক্ষা না করে সাথে সাথে সরাসরি ব্যাকআপ ও আপলোড শুরু করবে।
            // এটি ম্যানুয়াল "Backup Now" — ব্যবহারকারীর স্পষ্ট নির্দেশ বলে
            // Auto Backup টগল ও Wi-Fi Only এখানে প্রযোজ্য নয়।
            driveRepository.runImmediateFolderBackup(manual = true)

            val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
            val data = Data.Builder()
                .putLong("last_backup_time", lastBackupTime)
                .putBoolean("manual", true)
                .build()

            val backupRequest = OneTimeWorkRequestBuilder<MediaBackupWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "manual_backup_worker",
                ExistingWorkPolicy.REPLACE,
                backupRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scheduleCoreRefresh() {
        val refreshRequest = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "core_refresh_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            refreshRequest
        )
    }

    fun getBackupWorkInfo(): kotlinx.coroutines.flow.Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("media_backup_worker")
    }
}
