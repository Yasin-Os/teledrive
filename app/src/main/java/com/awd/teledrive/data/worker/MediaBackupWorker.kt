package com.awd.teledrive.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.SettingsRepository
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MediaBackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "backup_channel"
        // P1-14: ব্যর্থতা চিরকাল retry হবে না — 5 চেষ্টার পর failure,
        // যাতে WorkManager backoff শেষে কাজটা ছেড়ে দেয়। পরের
        // periodic রানে আবার চেষ্টা হবে।
        private const val MAX_RETRY_ATTEMPTS = 5
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MediaBackupWorkerEntryPoint {
        fun driveRepository(): DriveRepository
        fun secureSettings(): SecureSettings
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo(0, 0))

            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MediaBackupWorkerEntryPoint::class.java
            )
            val driveRepository = entryPoint.driveRepository()
            val secureSettings = entryPoint.secureSettings()

            // শিডিউল করা (অটোমেটিক) রান Auto Backup টগল ও Wi-Fi Only
            // মানবে; ম্যানুয়াল "Backup Now" রান (inputData manual=true)
            // ব্যবহারকারীর স্পষ্ট নির্দেশ বলে এগুলো বাইপাস করে।
            // ওয়ার্কারের constraints-ই প্রথম প্রতিরক্ষা; এটি দ্বিতীয়
            // স্তর — যাতে পুরোনো constraint-এ এনকিউ হওয়া রানও মোবাইল
            // ডাটা খরচ করতে না পারে।
            if (!inputData.getBoolean("manual", false)) {
                val settingsRepository = entryPoint.settingsRepository()
                if (!settingsRepository.isAutoBackupEnabled.value) {
                    return@withContext Result.success()
                }
                if (settingsRepository.isBackupWifiOnlyEnabled.value && !isNetworkUnmetered()) {
                    return@withContext Result.success()
                }
            }

            val backupFolders = secureSettings.getStringList("backup_folders")
            if (backupFolders.isEmpty()) return@withContext Result.success()

            val chatId = driveRepository.awaitSavedMessagesChatId()
            if (chatId == 0L) return@withContext retryOrFail()

            val customPrefs = applicationContext.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val deleteAfterBackup = customPrefs.getBoolean("delete_after_backup", false)

            val filesToBackup = driveRepository.collectTargetBackupFiles(backupFolders)
            if (filesToBackup.isEmpty()) return@withContext Result.success()

            // ১টি ১টি করে (1-by-1) ফাইল আপলোড করবে এবং শেষ হওয়া পর্যন্ত অপেক্ষা করবে
            filesToBackup.forEachIndexed { index, file ->
                if (!file.exists() || !file.isFile) return@forEachIndexed

                val currentCount = index + 1
                val totalCount = filesToBackup.size
                val originalPath = file.absolutePath
                val fileKey = "${originalPath}_${file.length()}"
                
                setProgress(workDataOf(
                    "progress" to currentCount.toFloat() / totalCount.toFloat(), 
                    "fileName" to file.name,
                    "current" to currentCount,
                    "total" to totalCount
                ))
                
                setForeground(createForegroundInfo(currentCount, totalCount))

                driveRepository.uploadFileAndWait(
                    filePath = originalPath,
                    originalFileName = file.name,
                    forceDeleteSource = deleteAfterBackup,
                    backupTrackingKey = fileKey
                )
                delay(300)
            }
            
            secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()

    private fun isNetworkUnmetered(): Boolean {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            false
        }
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        createNotificationChannel()
        val title = applicationContext.getString(R.string.auto_backup)
        val content = if (total > 0) {
            applicationContext.getString(R.string.backing_up_media_count, current, total)
        } else {
            applicationContext.getString(R.string.scanning_media)
        }
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setProgress(total, current, total == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.auto_backup)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
