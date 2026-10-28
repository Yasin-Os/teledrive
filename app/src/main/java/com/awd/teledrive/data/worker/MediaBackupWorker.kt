// File: app/src/main/java/com/awd/teledrive/data/worker/MediaBackupWorker.kt
package com.awd.teledrive.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.repository.TransferRepository
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
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MediaBackupWorkerEntryPoint {
        fun driveRepository(): DriveRepository
        fun transferRepository(): TransferRepository
        fun secureSettings(): SecureSettings
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(createForegroundInfo(0, 0))
            
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MediaBackupWorkerEntryPoint::class.java
            )
            val driveRepository = entryPoint.driveRepository()
            val transferRepository = entryPoint.transferRepository()
            val secureSettings = entryPoint.secureSettings()
            
            val backupFolders = secureSettings.getStringList("backup_folders")
            if (backupFolders.isEmpty()) return@withContext Result.success()

            // Saved Messages চ্যাট রুম প্রস্তুত না হওয়া পর্যন্ত অপেক্ষা করবে
            val chatId = driveRepository.awaitSavedMessagesChatId()
            if (chatId == 0L) return@withContext Result.retry()

            val customPrefs = applicationContext.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val deleteAfterBackup = customPrefs.getBoolean("delete_after_backup", false)

            // Tri-Engine স্ক্যানার দিয়ে টার্গেট ফোল্ডারের সব ফাইল বের করা হচ্ছে
            val filesToBackup = driveRepository.collectTargetBackupFiles(backupFolders)
            if (filesToBackup.isEmpty()) return@withContext Result.success()

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

                driveRepository.uploadFile(
                    filePath = originalPath,
                    originalFileName = file.name,
                    forceDeleteSource = deleteAfterBackup,
                    backupTrackingKey = fileKey
                )

                // ফাইলটি ক্লাউডে সম্পূর্ণ আপলোড হওয়া পর্যন্ত অপেক্ষা করবে
                var waitedMs = 0L
                val maxWaitMs = 20 * 60 * 1000L
                delay(800)
                while (waitedMs < maxWaitMs) {
                    val activeForFile = transferRepository.transfers.value.values.find {
                        !it.isDownload && (it.localPath == originalPath || it.fileName == file.name)
                    }
                    if (activeForFile == null || activeForFile.status == TransferRepository.Status.COMPLETED) {
                        break
                    }
                    if (activeForFile.status == TransferRepository.Status.CANCELLED ||
                        activeForFile.status.startsWith(TransferRepository.Status.FAILED)) {
                        break
                    }
                    delay(600)
                    waitedMs += 600
                }
            }
            
            secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
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
