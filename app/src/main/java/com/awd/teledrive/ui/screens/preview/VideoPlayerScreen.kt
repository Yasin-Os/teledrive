package com.awd.teledrive.ui.screens.preview

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.awd.teledrive.MainActivity
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.domain.model.DriveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

// ব্যাকগ্রাউন্ডে অডিও প্লে চালু রাখার সিঙ্গেলটন ইঞ্জিন
@AndroidOptIn(UnstableApi::class)
object BackgroundAudioEngine {
    var activePlayer: ExoPlayer? = null
    var activeUrl: String? = null

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        val existing = activePlayer
        if (existing != null) return existing

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { activePlayer = it }
    }

    fun stopAndRelease(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(2026)
        } catch (e: Exception) {}
        try {
            activePlayer?.stop()
            activePlayer?.release()
        } catch (e: Exception) {}
        activePlayer = null
        activeUrl = null
    }
}

private fun resetWindowBrightness(activity: Activity?) {
    activity?.window?.let { win ->
        val lp = win.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        win.attributes = lp
    }
}

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val telegramClient: TelegramClient,
    private val driveDao: DriveDao,
    private val driveRepository: DriveRepository
) : ViewModel() {

    @Volatile
    var cachedTdFile: TdApi.File? = null

    val playablePlaylist: StateFlow<List<DriveItem.File>> = driveRepository.getAllFiles()
        .map { files ->
            files.filter { !it.isEncrypted && (it.mimeType.startsWith("video/") || it.mimeType.startsWith("audio/")) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun startWatchingAndDownloading(chatId: Long, messageId: Long, initialFileId: Int) {
        cachedTdFile = null
        viewModelScope.launch {
            var activeFileId = initialFileId
            if (chatId != 0L && messageId != 0L) {
                val msgDef = CompletableDeferred<TdApi.Object>()
                telegramClient.send(TdApi.GetMessage(chatId, messageId)) { msgDef.complete(it) }
                val msg = msgDef.await()
                if (msg is TdApi.Message) {
                    val f = when (val c = msg.content) {
                        is TdApi.MessageVideo -> c.video.video
                        is TdApi.MessageDocument -> c.document.document
                        is TdApi.MessageAudio -> c.audio.audio
                        is TdApi.MessageAnimation -> c.animation.animation
                        else -> null
                    }
                    if (f != null) {
                        activeFileId = f.id
                        cachedTdFile = f
                    }
                }
            }

            if (activeFileId != 0) {
                telegramClient.send(TdApi.DownloadFile(activeFileId, 32, 0, 0, false))
            }

            telegramClient.fileUpdates.collect { update ->
                val f = update.file
                if (f.id == activeFileId) {
                    cachedTdFile = f
                    if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
                        driveDao.updateLocalPath(f.id, f.local.path)
                        if (f.remote.uniqueId.isNotEmpty()) {
                            driveDao.updateLocalPathByUniqueId(f.remote.uniqueId, f.local.path)
                        }
                    }
                }
            }
        }
    }

    fun markOfflinePath(fileId: Int, uniqueId: String, path: String) {
        viewModelScope.launch {
            driveDao.updateLocalPath(fileId, path)
            if (uniqueId.isNotEmpty()) {
                driveDao.updateLocalPathByUniqueId(uniqueId, path)
            }
        }
    }

    fun toggleStarByMessageId(messageId: Long, chatId: Long) {
        viewModelScope.launch {
            val item = playablePlaylist.value.find { it.id == messageId }
            if (item != null) {
                driveRepository.toggleStarred(item)
            } else if (messageId != 0L && chatId != 0L) {
                val ent = driveDao.getItemById(messageId, chatId)
                if (ent != null) {
                    driveDao.updateStarred(messageId, chatId, !ent.isStarred)
                }
            }
        }
    }

    fun getAdjacentMediaUrl(currentUrl: String, next: Boolean): String? {
        val list = playablePlaylist.value
        if (list.isEmpty()) return null

        val currentIdx = list.indexOfFirst { item ->
            if (currentUrl.startsWith("tdstream://")) {
                val parts = currentUrl.removePrefix("tdstream://").split("|")
                val msgId = parts.getOrNull(1)?.toLongOrNull() ?: -1L
                val fId = parts.getOrNull(2)?.toIntOrNull() ?: -1
                item.id == msgId || item.telegramFileId == fId
            } else {
                item.localPath == currentUrl || currentUrl.endsWith(item.name)
            }
        }

        val targetIndex = if (currentIdx == -1) 0
        else if (next) (currentIdx + 1) % list.size
        else (currentIdx - 1 + list.size) % list.size

        val target = list[targetIndex]
        val localFile = target.localPath?.let { File(it) }
        return if (localFile != null && localFile.exists()) {
            localFile.absolutePath
        } else {
            "tdstream://${target.parentChatId}|${target.id}|${target.telegramFileId}|${target.size}|${target.name}"
        }
    }
}

@AndroidOptIn(UnstableApi::class)
class ZeroLagTdlibDataSource(
    private val viewModel: VideoPlayerViewModel,
    private val chatId: Long,
    private val messageId: Long,
    private var fileId: Int,
    private val expectedSize: Long
) : BaseDataSource(true) {

    private var raf: RandomAccessFile? = null
    private var openedFilePath: String? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened = false

    private fun queryTdFileOnce(): TdApi.File? = runBlocking {
        if (fileId == 0 && chatId != 0L && messageId != 0L) {
            val msgDef = CompletableDeferred<TdApi.Object>()
            viewModel.telegramClient.send(TdApi.GetMessage(chatId, messageId)) { msgDef.complete(it) }
            val msg = msgDef.await()
            if (msg is TdApi.Message) {
                val f = when (val c = msg.content) {
                    is TdApi.MessageVideo -> c.video.video
                    is TdApi.MessageDocument -> c.document.document
                    is TdApi.MessageAudio -> c.audio.audio
                    is TdApi.MessageAnimation -> c.animation.animation
                    else -> null
                }
                if (f != null) {
                    fileId = f.id
                    viewModel.cachedTdFile = f
                    return@runBlocking f
                }
            }
        }
        if (fileId == 0) return@runBlocking null
        val def = CompletableDeferred<TdApi.Object>()
        viewModel.telegramClient.send(TdApi.GetFile(fileId)) { def.complete(it) }
        val result = def.await() as? TdApi.File
        if (result != null) viewModel.cachedTdFile = result
        result
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentPosition = dataSpec.position
        val file = viewModel.cachedTdFile ?: queryTdFileOnce()
        val totalSize = file?.expectedSize?.takeIf { it > 0 } ?: file?.size?.takeIf { it > 0 } ?: expectedSize

        if (file != null) {
            if (!file.local.isDownloadingCompleted) {
                viewModel.telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 0, false))
            } else if (file.local.path.isNotEmpty()) {
                viewModel.markOfflinePath(file.id, file.remote.uniqueId, file.local.path)
            }
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalSize > 0) {
            totalSize - currentPosition
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else length

        var lastRequestedOffset = -1L

        while (opened) {
            val file = viewModel.cachedTdFile ?: queryTdFileOnce() ?: return C.RESULT_END_OF_INPUT
            val local = file.local

            if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
                if (raf == null || openedFilePath != local.path) {
                    val f = File(local.path)
                    if (f.exists()) {
                        raf?.close()
                        raf = RandomAccessFile(f, "r")
                        openedFilePath = local.path
                        viewModel.markOfflinePath(file.id, file.remote.uniqueId, local.path)
                    }
                }
                val activeRaf = raf
                if (activeRaf != null) {
                    activeRaf.seek(currentPosition)
                    val readBytes = activeRaf.read(buffer, offset, toRead)
                    if (readBytes == -1) return C.RESULT_END_OF_INPUT
                    currentPosition += readBytes
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= readBytes
                    bytesTransferred(readBytes)
                    return readBytes
                }
            }

            val availStart = local.downloadOffset
            val availEnd = local.downloadOffset + local.downloadedPrefixSize

            if (local.path.isNotEmpty() && currentPosition >= availStart && currentPosition < availEnd) {
                if (raf == null || openedFilePath != local.path) {
                    val f = File(local.path)
                    if (f.exists()) {
                        raf?.close()
                        raf = RandomAccessFile(f, "r")
                        openedFilePath = local.path
                    }
                }
                val activeRaf = raf
                if (activeRaf != null) {
                    val readable = minOf(toRead.toLong(), availEnd - currentPosition).toInt()
                    if (readable > 0) {
                        activeRaf.seek(currentPosition)
                        val readBytes = activeRaf.read(buffer, offset, readable)
                        if (readBytes > 0) {
                            currentPosition += readBytes
                            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= readBytes
                            bytesTransferred(readBytes)
                            return readBytes
                        }
                    }
                }
            }

            if (!local.isDownloadingActive || currentPosition < availStart || (currentPosition - availEnd) > 2 * 1024 * 1024L) {
                if (lastRequestedOffset != currentPosition) {
                    viewModel.telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 0, false))
                    lastRequestedOffset = currentPosition
                }
            }

            try {
                Thread.sleep(40)
                queryTdFileOnce()
            } catch (e: InterruptedException) {
                return C.RESULT_END_OF_INPUT
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = Uri.parse("tdstream://file/$fileId")

    override fun close() {
        opened = false
        try {
            raf?.close()
        } catch (e: Exception) {}
        raf = null
        openedFilePath = null
    }
}

@AndroidOptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    url: String,
    onBack: () -> Unit,
    viewModel: VideoPlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playlist by viewModel.playablePlaylist.collectAsState()

    var activeMediaUrl by remember(url) { mutableStateOf(url) }

    val isStreamUrl = activeMediaUrl.startsWith("tdstream://")
    val streamParts = remember(activeMediaUrl) {
        if (isStreamUrl) activeMediaUrl.removePrefix("tdstream://").split("|") else emptyList()
    }
    val chatId = streamParts.getOrNull(0)?.toLongOrNull() ?: 0L
    val messageId = streamParts.getOrNull(1)?.toLongOrNull() ?: 0L
    val telegramFileId = streamParts.getOrNull(2)?.toIntOrNull() ?: 0
    val expectedSize = streamParts.getOrNull(3)?.toLongOrNull() ?: 0L
    val fileName = if (isStreamUrl) (streamParts.getOrNull(4) ?: "Media") else activeMediaUrl.substringAfterLast('/')

    val isNativeAudioFile = remember(fileName) {
        val lower = fileName.lowercase()
        lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
        lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".aac")
    }

    var isAudioPlayerMode by remember(isNativeAudioFile) { mutableStateOf(isNativeAudioFile) }
    var showAudioTooltip by remember { mutableStateOf(!isNativeAudioFile) }

    val exoPlayer = remember { BackgroundAudioEngine.getOrCreatePlayer(context) }

    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isMuted by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var isRepeatOne by remember { mutableStateOf(false) }
    var isStarred by remember { mutableStateOf(false) }

    var activeGestureMode by remember { mutableStateOf(GestureMode.NONE) }
    var verticalGestureProgress by remember { mutableFloatStateOf(0f) }
    var seekOverlayText by remember { mutableStateOf<String?>(null) }

    var doubleTapSkipSeconds by remember { mutableIntStateOf(0) }
    var isSkipForward by remember { mutableStateOf(true) }

    LaunchedEffect(activeMediaUrl) {
        isStarred = playlist.find { it.id == messageId || it.name == fileName }?.isStarred == true
        // যদি আগে থেকেই একই অডিও ব্যাকগ্রাউন্ডে বাজতে থাকে তবে রিস্টার্ট করবে না
        if (BackgroundAudioEngine.activeUrl == activeMediaUrl && exoPlayer.isPlaying) {
            duration = exoPlayer.duration.coerceAtLeast(0L)
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            return@LaunchedEffect
        }

        BackgroundAudioEngine.activeUrl = activeMediaUrl
        currentPosition = 0L
        duration = 0L
        if (isStreamUrl) {
            viewModel.startWatchingAndDownloading(chatId, messageId, telegramFileId)
            val factory = DataSource.Factory {
                ZeroLagTdlibDataSource(
                    viewModel = viewModel,
                    chatId = chatId,
                    messageId = messageId,
                    fileId = telegramFileId,
                    expectedSize = expectedSize
                )
            }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri("tdstream://file/$telegramFileId"))
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(activeMediaUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(isAudioPlayerMode, isPlaying, fileName) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mycloud_bg_audio"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Background Audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (isAudioPlayerMode && isPlaying) {
            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(context, channelId)
                .setContentTitle(fileName.substringBeforeLast('.'))
                .setContentText("<unknown> • Playing in background")
                .setSmallIcon(R.drawable.ic_mycloud_logo)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            nm.notify(2026, notif)
        } else if (!isAudioPlayerMode) {
            nm.cancel(2026)
        }
    }

    // ভিডিও থেকে বের হলে সাথে সাথে ব্রাইটনেস রিসেট হবে, কিন্তু অডিও মোডে থাকলে অডিও ব্যাকগ্রাউন্ডে বাজতে থাকবে!
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            resetWindowBrightness(activity)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exoPlayer.removeListener(listener)
            // শুধুমাত্র ভিডিও মোডে থাকলে প্লেয়ার বন্ধ হবে; অডিও মোডে থাকলে ব্যাকগ্রাউন্ডে চলতে থাকবে!
            if (!isAudioPlayerMode) {
                BackgroundAudioEngine.stopAndRelease(context)
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (activeGestureMode != GestureMode.SEEK) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            if (exoPlayer.duration > 0) duration = exoPlayer.duration
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, isLocked, isAudioPlayerMode) {
        if (controlsVisible && isPlaying && !isLocked && !isAudioPlayerMode) {
            delay(4000)
            controlsVisible = false
            showAudioTooltip = false
        }
    }

    LaunchedEffect(doubleTapSkipSeconds) {
        if (doubleTapSkipSeconds != 0) {
            delay(800)
            doubleTapSkipSeconds = 0
        }
    }

    if (isAudioPlayerMode) {
        LaunchedEffect(Unit) { resetWindowBrightness(activity) }
        PlayItVinylAudioPlayerUI(
            fileName = fileName,
            isPlaying = isPlaying,
            currentPosition = currentPosition,
            duration = duration,
            isRepeatOne = isRepeatOne,
            isStarred = isStarred,
            isVideoSource = !isNativeAudioFile,
            onBack = {
                // অডিও মোড থেকে ব্যাকে গেলে অডিও ব্যাকগ্রাউন্ডে চলতে থাকবে
                resetWindowBrightness(activity)
                onBack()
            },
            onSwitchToVideo = { isAudioPlayerMode = false },
            onPlayPause = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(2026)
                } else {
                    exoPlayer.play()
                }
            },
            onSeek = { target ->
                currentPosition = target
                exoPlayer.seekTo(target)
            },
            onPrevTrack = {
                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = false)?.let { activeMediaUrl = it }
            },
            onNextTrack = {
                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = true)?.let { activeMediaUrl = it }
            },
            onToggleRepeat = {
                isRepeatOne = !isRepeatOne
                exoPlayer.repeatMode = if (isRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            },
            onToggleStar = {
                isStarred = !isStarred
                viewModel.toggleStarByMessageId(messageId, chatId)
            },
            onShare = {
                val path = viewModel.cachedTdFile?.local?.path ?: activeMediaUrl.takeIf { !isStreamUrl }
                if (!path.isNullOrEmpty()) {
                    FileSharingHelper.shareFile(context, path, "audio/*")
                }
            }
        )
        return
    }

    val skipWaveTransition = rememberInfiniteTransition(label = "SkipWave")
    val skipArrowSlide by skipWaveTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SkipArrowSlide"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val totalDur = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            if (offset.x < size.width / 2f) {
                                isSkipForward = false
                                doubleTapSkipSeconds = if (doubleTapSkipSeconds < 0) doubleTapSkipSeconds - 10 else -10
                                val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                            } else {
                                isSkipForward = true
                                doubleTapSkipSeconds = if (doubleTapSkipSeconds > 0) doubleTapSkipSeconds + 10 else 10
                                val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(totalDur)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked, duration) {
                if (!isLocked) {
                    var startX = 0f
                    var seekStartMs = 0L
                    var accumulatedX = 0f
                    var volumeFloat = 0.5f

                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            seekStartMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                            accumulatedX = 0f
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            volumeFloat = curVol.toFloat() / maxVol.toFloat()
                            activeGestureMode = GestureMode.NONE
                        },
                        onDragEnd = {
                            activeGestureMode = GestureMode.NONE
                            seekOverlayText = null
                        },
                        onDragCancel = {
                            activeGestureMode = GestureMode.NONE
                            seekOverlayText = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (activeGestureMode == GestureMode.NONE) {
                                activeGestureMode = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                    GestureMode.SEEK
                                } else if (startX < size.width / 2f) {
                                    GestureMode.BRIGHTNESS
                                } else {
                                    GestureMode.VOLUME
                                }
                            }

                            when (activeGestureMode) {
                                GestureMode.SEEK -> {
                                    accumulatedX += dragAmount.x
                                    val fullWidthSeekMs = 90_000f
                                    val deltaMs = ((accumulatedX / size.width.toFloat()) * fullWidthSeekMs).toLong()
                                    val totalDur = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                    val targetMs = (seekStartMs + deltaMs).coerceIn(0L, totalDur)
                                    val diffSec = (targetMs - seekStartMs) / 1000L
                                    val sign = if (diffSec >= 0) "+" else "-"
                                    val diffStr = formatDuration(abs(diffSec) * 1000L)
                                    seekOverlayText = "${formatDuration(targetMs)}[$sign$diffStr]"
                                    currentPosition = targetMs
                                    exoPlayer.seekTo(targetMs)
                                }
                                GestureMode.BRIGHTNESS -> {
                                    val delta = -dragAmount.y / (size.height * 0.7f)
                                    val window = activity?.window
                                    val lp = window?.attributes
                                    val curBright = lp?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                    val newBright = (curBright + delta).coerceIn(0.01f, 1.0f)
                                    lp?.screenBrightness = newBright
                                    window?.attributes = lp
                                    verticalGestureProgress = newBright
                                }
                                GestureMode.VOLUME -> {
                                    val delta = -dragAmount.y / (size.height * 0.7f)
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    volumeFloat = (volumeFloat + delta).coerceIn(0f, 1f)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeFloat * maxVol).toInt(), 0)
                                    verticalGestureProgress = volumeFloat
                                }
                                GestureMode.NONE -> {}
                            }
                        }
                    )
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && activeGestureMode == GestureMode.NONE) {
            CircularProgressIndicator(
                color = Color(0xFF2ECC71),
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(46.dp).align(Alignment.Center)
            )
        }

        // বক্স ছাড়া PLAYit স্টাইলে স্মুথ অ্যানিমেশনে ডাবল-ট্যাপ সেকেন্ড ইন্ডিকেটর (» +10s / « -10s)
        AnimatedVisibility(
            visible = doubleTapSkipSeconds != 0,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
            exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 1.1f),
            modifier = Modifier
                .align(if (isSkipForward) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 56.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (!isSkipForward) {
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowLeft,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(36.dp)
                            .offset(x = skipArrowSlide.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (isSkipForward) "+${abs(doubleTapSkipSeconds)}s" else "-${abs(doubleTapSkipSeconds)}s",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.75f),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f
                        )
                    )
                )
                if (isSkipForward) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardDoubleArrowRight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(36.dp)
                            .offset(x = skipArrowSlide.dp)
                    )
                }
            }
        }

        // আলো ও সাউন্ড বাড়ানো-কমানোর ভার্টিক্যাল বার (PLAYit স্টাইল)
        if (activeGestureMode == GestureMode.BRIGHTNESS || activeGestureMode == GestureMode.VOLUME) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (activeGestureMode == GestureMode.BRIGHTNESS) Icons.Default.WbSunny else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(125.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.35f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(verticalGestureProgress.coerceIn(0f, 1f))
                                .align(Alignment.BottomCenter)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    }
                    Text(
                        text = "${(verticalGestureProgress * 100).toInt()}",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(start = 54.dp)
                    )
                }
            }
        }

        if (activeGestureMode == GestureMode.SEEK && seekOverlayText != null) {
            Text(
                text = seekOverlayText!!,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (isLocked && controlsVisible) {
            Surface(
                onClick = { isLocked = false },
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.78f)
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                resetWindowBrightness(activity)
                                BackgroundAudioEngine.stopAndRelease(context)
                                onBack()
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                showAudioTooltip = false
                                resetWindowBrightness(activity)
                                isAudioPlayerMode = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headset,
                                contentDescription = "Play as Audio",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    if (showAudioTooltip) {
                        Surface(
                            onClick = {
                                showAudioTooltip = false
                                resetWindowBrightness(activity)
                                isAudioPlayerMode = true
                            },
                            color = Color(0xFF1DB954),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "Background play / Play as audio",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        onClick = {
                            isMuted = !isMuted
                            exoPlayer.volume = if (isMuted) 0f else 1f
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Mute",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = { isLocked = true },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Lock",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        onClick = {
                            Toast.makeText(context, "Snapshot saved", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = "Cut",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            isLandscape = !isLandscape
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatDuration(currentPosition),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                            onValueChange = { fraction ->
                                if (duration > 0) {
                                    val target = (fraction * duration).toLong()
                                    currentPosition = target
                                    exoPlayer.seekTo(target)
                                }
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF2ECC71),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = formatDuration(duration),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(BorderStroke(1.8.dp, Color.White), CircleShape)
                                .clickable {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(18.dp))

                        IconButton(
                            onClick = {
                                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = false)?.let { activeMediaUrl = it }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(26.dp))
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        IconButton(
                            onClick = {
                                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = true)?.let { activeMediaUrl = it }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = if (playbackSpeed == 1.0f) "Speed" else "${playbackSpeed}x",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val nextSpeed = when (playbackSpeed) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        2.0f -> 0.75f
                                        else -> 1.0f
                                    }
                                    playbackSpeed = nextSpeed
                                    exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Surface(
                            onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            color = Color.White,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.size(width = 28.dp, height = 20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SyncAlt,
                                    contentDescription = "Fit",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(18.dp))

                        IconButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                                    } catch (e: Exception) {}
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PIP", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayItVinylAudioPlayerUI(
    fileName: String,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isRepeatOne: Boolean,
    isStarred: Boolean,
    isVideoSource: Boolean,
    onBack: () -> Unit,
    onSwitchToVideo: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleStar: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "VinylRotation")
    val discAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "DiscAngle"
    )
    val eqPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "EqPhase"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1B1F11),
                        Color(0xFF282D16),
                        Color(0xFF12140B)
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fileName.substringBeforeLast('.'),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "<unknown>",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(300.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val discRadius = size.minDimension * 0.35f
                val barStartRadius = discRadius + 14f
                val maxBarLength = size.minDimension * 0.11f

                val barCount = 84
                for (i in 0 until barCount) {
                    val angleDeg = (i * 360f / barCount) + (if (isPlaying) discAngle * 0.1f else 0f)
                    val angleRad = angleDeg * (PI.toFloat() / 180f)
                    val wave = if (isPlaying) {
                        0.35f + 0.65f * abs(sin(angleRad * 6f + eqPhase) * cos(angleRad * 3f - eqPhase))
                    } else {
                        0.25f
                    }
                    val len = maxBarLength * wave
                    val sx = cx + barStartRadius * cos(angleRad)
                    val sy = cy + barStartRadius * sin(angleRad)
                    val ex = cx + (barStartRadius + len) * cos(angleRad)
                    val ey = cy + (barStartRadius + len) * sin(angleRad)

                    drawLine(
                        color = Color(0xFF2ECC71),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 4.2f,
                        cap = StrokeCap.Round
                    )
                }

                drawCircle(
                    color = Color(0xFF161616),
                    radius = discRadius,
                    center = Offset(cx, cy)
                )

                for (g in 1..6) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = discRadius * (0.45f + g * 0.08f),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f)
                    )
                }

                val labelRadius = discRadius * 0.42f
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF64B5F6),
                            Color(0xFF81C784),
                            Color(0xFFDCE775)
                        ),
                        start = Offset(cx - labelRadius, cy - labelRadius),
                        end = Offset(cx + labelRadius, cy + labelRadius)
                    ),
                    radius = labelRadius,
                    center = Offset(cx, cy)
                )

                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = labelRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )

                drawCircle(
                    color = Color(0xFF14160B),
                    radius = discRadius * 0.07f,
                    center = Offset(cx, cy)
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleStar) {
                    Icon(
                        imageVector = if (isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = "Favorite",
                        tint = if (isStarred) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = {
                    Toast.makeText(context, "Hi-Fi Audio Enhancer Active", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Equalizer",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = {
                    Toast.makeText(context, "Background Play Active", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Sleep Timer",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = {
                    if (isVideoSource) onSwitchToVideo()
                }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Switch to Video",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatDuration(currentPosition),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
                Slider(
                    value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        if (duration > 0) onSeek((fraction * duration).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = formatDuration(duration),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleRepeat) {
                    Icon(
                        imageVector = if (isRepeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(onClick = onPrevTrack) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(66.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(onClick = onNextTrack) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = {
                    if (isVideoSource) onSwitchToVideo()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
