package com.awd.teledrive.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
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
import java.io.File

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

            val customPrefs = applicationContext.getSharedPreferences("mycloud_custom_prefs", Context.MODE_PRIVATE)
            val deleteAfterBackup = customPrefs.getBoolean("delete_after_backup", false)
            val backedUpSet = customPrefs.getStringSet("backed_up_paths_v2", emptySet())?.toMutableSet() ?: mutableSetOf()

            val lastBackupTime = secureSettings.getLong("last_backup_time", 0L)
            val filesToBackup = scanFoldersForFiles(backupFolders, lastBackupTime, backedUpSet)
            
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

                driveRepository.uploadFile(originalPath, file.name)

                // ফাইলটি ক্লাউডে সম্পূর্ণ আপলোড হওয়া পর্যন্ত অপেক্ষা করবে
                var waitedMs = 0L
                val maxWaitMs = 15 * 60 * 1000L
                delay(1500)
                while (waitedMs < maxWaitMs) {
                    val activeForFile = transferRepository.transfers.value.values.find {
                        !it.isDownload && it.fileName == file.name
                    }
                    if (activeForFile == null || activeForFile.status == TransferRepository.Status.COMPLETED) {
                        break
                    }
                    if (activeForFile.status == TransferRepository.Status.CANCELLED ||
                        activeForFile.status.startsWith(TransferRepository.Status.FAILED)) {
                        break
                    }
                    delay(1000)
                    waitedMs += 1000
                }

                backedUpSet.add(fileKey)
                customPrefs.edit().putStringSet("backed_up_paths_v2", backedUpSet).apply()

                // ক্লাউডে আপলোড শেষ হলে ফোন স্টোরেজ থেকে অটো ডিলিট
                if (deleteAfterBackup) {
                    deleteFileFromPhoneStorage(file)
                }
            }
            
            secureSettings.saveLong("last_backup_time", System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun deleteFileFromPhoneStorage(file: File) {
        val path = file.absolutePath
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {}

        try {
            val externalUri = MediaStore.Files.getContentUri("external")
            applicationContext.contentResolver.delete(
                externalUri,
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path)
            )
        } catch (e: Exception) {}

        try {
            MediaScannerConnection.scanFile(applicationContext, arrayOf(path), null, null)
        } catch (e: Exception) {}
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

    private fun scanFoldersForFiles(
        folders: List<String>,
        sinceTimestamp: Long,
        backedUpSet: Set<String>
    ): List<File> {
        val result = mutableListOf<File>()

        folders.forEach { folderPath ->
            val root = File(folderPath)
            if (root.exists() && root.isDirectory) {
                collectFilesRecursively(root, backedUpSet, result)
            }
        }

        if (result.isEmpty()) {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            val uris = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            )
            uris.forEach { uri ->
                try {
                    applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(idx) ?: continue
                            if (folders.any { path.startsWith(it) }) {
                                val f = File(path)
                                val key = "${f.absolutePath}_${f.length()}"
                                if (f.exists() && f.length() > 0 && !backedUpSet.contains(key)) {
                                    result.add(f)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        return result.distinctBy { it.absolutePath }.sortedBy { it.lastModified() }
    }

    private fun collectFilesRecursively(
        dir: File,
        backedUpSet: Set<String>,
        outList: MutableList<File>
    ) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                collectFilesRecursively(child, backedUpSet, outList)
            } else if (child.isFile && child.length() > 0) {
                val key = "${child.absolutePath}_${child.length()}"
                if (!backedUpSet.contains(key)) {
                    outList.add(child)
                }
            }
        }
    }
}
