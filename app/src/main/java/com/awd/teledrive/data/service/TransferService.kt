package com.awd.teledrive.data.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.awd.teledrive.MainActivity
import com.awd.teledrive.R
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.TransferRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        private const val TAG = "TransferService"
        const val ACTION_CANCEL_TRANSFER = "com.awd.teledrive.ACTION_CANCEL_TRANSFER"
        const val ACTION_CANCEL_ALL = "com.awd.teledrive.ACTION_CANCEL_ALL"
        const val ACTION_UPDATE_DOWNLOAD_FINISHED = "com.awd.teledrive.ACTION_UPDATE_DOWNLOAD_FINISHED"
        const val EXTRA_UNIQUE_ID = "extra_unique_id"
        // Set by UpdateRepository when it starts this service as a keep-alive
        // for an in-app update download. An update download is NOT a
        // TransferRepository transfer, so without this the service would see
        // an empty transfer list and stop itself mid-download (P1-16).
        const val EXTRA_KEEP_ALIVE_FOR_UPDATE = "extra_keep_alive_for_update"
    }

    // True while an in-app update APK download is in progress. Only set from
    // the start intent (EXTRA_KEEP_ALIVE_FOR_UPDATE) and cleared by the
    // ACTION_UPDATE_DOWNLOAD_FINISHED intent sent by UpdateRepository.
    // H16: exposed as StateFlow so collectLatest reacts immediately without
    // blocking inside a while-loop.
    private val updateDownloadActive = MutableStateFlow(false)
    private var isInForeground = false

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isInForeground = true
            true
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Foreground service start not allowed", e)
            } else {
                Log.w(TAG, "startForeground failed", e)
            }
            isInForeground = false
            false
        }
    }

    private fun stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        } finally {
            isInForeground = false
        }
    }

    private fun activeTransfersFrom(transfers: Map<String, TransferInfo>): List<TransferInfo> {
        return transfers.values.filter {
            it.status != TransferRepository.Status.COMPLETED &&
                it.status != TransferRepository.Status.CANCELLED &&
                !it.status.startsWith(TransferRepository.Status.FAILED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // H16: ensure foreground contract is satisfied on every start command
        // (e.g. if started via startForegroundService) and never restart sticky
        // with a null intent after process kill.
        if (!isInForeground) {
            val initialNotification = createNotification(getString(R.string.refreshing), 0f, true)
            if (!startForegroundSafely(initialNotification)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_UPDATE_DOWNLOAD_FINISHED -> {
                updateDownloadActive.value = false
            }
            ACTION_CANCEL_TRANSFER -> {
                val uniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID)
                if (uniqueId != null) {
                    transferRepository.cancelTransfer(uniqueId)
                }
            }
            ACTION_CANCEL_ALL -> {
                transferRepository.transfers.value.values
                    .filter {
                        it.status == TransferRepository.Status.DOWNLOADING ||
                            it.status == TransferRepository.Status.UPLOADING ||
                            it.status == TransferRepository.Status.QUEUED
                    }
                    .forEach { transferRepository.cancelTransfer(it.remoteUniqueId) }
            }
        }
        // Only ever sets the flag: an intent without this extra (e.g. a
        // normal transfer start from DriveRepository) must not clear an
        // update download that is still in progress.
        if (intent?.getBooleanExtra(EXTRA_KEEP_ALIVE_FOR_UPDATE, false) == true) {
            updateDownloadActive.value = true
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val initialNotification = createNotification(getString(R.string.refreshing), 0f, true)
        if (!startForegroundSafely(initialNotification)) {
            stopSelf()
            return
        }

        serviceScope.launch {
            combine(transferRepository.transfers, updateDownloadActive) { transfers, updateActive ->
                transfers to updateActive
            }.collectLatest { (transfers, updateActive) ->
                val transferring = activeTransfersFrom(transfers)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (transferring.isEmpty()) {
                    // P1-16 + H16: while an in-app update download is active,
                    // simply wait for the next flow emission instead of looping
                    // inside collectLatest. When idle, wait a short grace delay
                    // (automatically cancelled by collectLatest if a new
                    // transfer or update starts) before stopping the service.
                    if (updateActive) return@collectLatest
                    delay(3000)
                    val stillEmpty = activeTransfersFrom(transferRepository.transfers.value).isEmpty()
                    if (stillEmpty && !updateDownloadActive.value) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                } else {
                    val activeItem = transferring.firstOrNull {
                        it.status == TransferRepository.Status.UPLOADING || it.status == TransferRepository.Status.DOWNLOADING
                    } ?: transferring.first()

                    val queuedCount = transferring.size - 1
                    val percentage = (activeItem.progress * 100).toInt()
                    val baseText = if (activeItem.isDownload) {
                        getString(R.string.downloading_file, activeItem.fileName)
                    } else {
                        getString(R.string.uploading_file, activeItem.fileName)
                    }
                    val suffix = if (queuedCount > 0) " (+$queuedCount in queue)" else ""

                    val notification = createNotification(
                        "$baseText ($percentage%)$suffix",
                        activeItem.progress,
                        true,
                        activeItem.remoteUniqueId,
                        queuedCount > 0
                    )

                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    // H16: required for targetSdk 35 dataSync foreground services (6-hour cap)
    // to stop cleanly instead of crashing with RemoteServiceException.
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timeout reached (startId=$startId); stopping")
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached (startId=$startId, fgsType=$fgsType); stopping")
        stopForegroundCompat()
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotification(content: String, progress: Float, ongoing: Boolean, uniqueId: String? = null, isMulti: Boolean = false): Notification {
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
            if (uniqueId != null && !isMulti) {
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
