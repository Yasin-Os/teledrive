// File: app/src/main/java/com/awd/teledrive/data/service/TransferService.kt
package com.awd.teledrive.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.awd.teledrive.MainActivity
import com.awd.teledrive.R
import com.awd.teledrive.data.repository.TransferRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TransferService : Service() {

    @Inject
    lateinit var transferRepository: TransferRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "transfer_channel"

    companion object {
        const val ACTION_CANCEL_TRANSFER = "com.awd.teledrive.ACTION_CANCEL_TRANSFER"
        const val ACTION_CANCEL_ALL = "com.awd.teledrive.ACTION_CANCEL_ALL"
        const val EXTRA_UNIQUE_ID = "extra_unique_id"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_TRANSFER -> {
                val uniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID)
                if (uniqueId != null) {
                    transferRepository.cancelTransfer(uniqueId)
                }
            }
            ACTION_CANCEL_ALL -> {
                transferRepository.transfers.value.values
                    .filter { it.status == TransferRepository.Status.DOWNLOADING || it.status == TransferRepository.Status.UPLOADING || it.status == TransferRepository.Status.QUEUED }
                    .forEach { transferRepository.cancelTransfer(it.remoteUniqueId) }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = createNotification(getString(R.string.refreshing), 0f, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        serviceScope.launch {
            delay(800)
            transferRepository.transfers.collectLatest { transfers ->
                val transferring = transfers.values.filter { 
                    it.status != TransferRepository.Status.COMPLETED && 
                    it.status != TransferRepository.Status.CANCELLED && 
                    !it.status.startsWith(TransferRepository.Status.FAILED)
                }
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                if (transferring.isEmpty()) {
                    // পরবর্তী ফাইল কিউ থেকে আসার জন্য ৩ সেকেন্ড অপেক্ষা করবে, যাতে বারবার সার্ভিস বন্ধ-চালু না হয়
                    delay(3000)
                    val stillEmpty = transferRepository.transfers.value.values.none {
                        it.status != TransferRepository.Status.COMPLETED &&
                        it.status != TransferRepository.Status.CANCELLED &&
                        !it.status.startsWith(TransferRepository.Status.FAILED)
                    }
                    if (stillEmpty) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    val totalBytes = transferring.sumOf { it.totalSize }
                    val downloadedBytes = transferring.sumOf { it.downloadedSize }
                    val avgProgress = transferring.map { it.progress }.average().toFloat()
                    val progress = if (totalBytes > 0 && downloadedBytes > 0) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        avgProgress.coerceIn(0f, 1f)
                    }
                    
                    val percentage = (progress * 100).toInt()
                    
                    val notification = if (transferring.size == 1) {
                        val transfer = transferring.first()
                        val text = when {
                            transfer.status.startsWith("Splitting") || transfer.status.startsWith("Memecah") -> getString(R.string.splitting_file, transfer.fileName)
                            transfer.status.startsWith("Merging") || transfer.status.startsWith("Menggabungkan") -> getString(R.string.merging_file, transfer.fileName)
                            transfer.isDownload -> getString(R.string.downloading_file, transfer.fileName)
                            else -> getString(R.string.uploading_file, transfer.fileName)
                        }
                        createNotification(
                            "$text ($percentage%)",
                            progress,
                            true,
                            transfer.remoteUniqueId
                        )
                    } else {
                        val downloads = transferring.count { it.isDownload }
                        val uploads = transferring.size - downloads
                        val text = when {
                            downloads > 0 && uploads > 0 -> getString(R.string.transferring_count, transferring.size)
                            downloads > 0 -> getString(R.string.downloading_count, downloads)
                            else -> getString(R.string.uploading_count, uploads)
                        }
                        createNotification("$text ($percentage%)", progress, true, null, true)
                    }
                    
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun createNotification(content: String, progress: Float, ongoing: Boolean, uniqueId: String? = null, isMulti: Boolean = false): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else R.drawable.ic_mycloud_logo)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )

        if (ongoing) {
            if (uniqueId != null) {
                val cancelIntent = Intent(this, TransferService::class.java).apply {
                    action = ACTION_CANCEL_TRANSFER
                    putExtra(EXTRA_UNIQUE_ID, uniqueId)
                }
                val pendingCancel = PendingIntent.getService(
                    this, uniqueId.hashCode(), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), pendingCancel)
            } else if (isMulti) {
                val cancelAllIntent = Intent(this, TransferService::class.java).apply {
                    action = ACTION_CANCEL_ALL
                }
                val pendingCancelAll = PendingIntent.getService(
                    this, 1002, cancelAllIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_all), pendingCancelAll)
            }
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfers),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
