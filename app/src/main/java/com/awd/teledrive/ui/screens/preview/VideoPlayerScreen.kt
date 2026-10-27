package com.awd.teledrive.ui.screens.preview

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.remote.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val telegramClient: TelegramClient,
    private val driveDao: DriveDao
) : ViewModel() {

    private val _resolvedLocalPath = MutableStateFlow<String?>(null)
    val resolvedLocalPath = _resolvedLocalPath.asStateFlow()

    fun observeFile(chatId: Long, messageId: Long, initialFileId: Int) {
        viewModelScope.launch {
            var activeFileId = initialFileId
            if (chatId != 0L && messageId != 0L) {
                val msgDef = CompletableDeferred<TdApi.Object>()
                telegramClient.send(TdApi.GetMessage(chatId, messageId)) { msgDef.complete(it) }
                val msg = msgDef.await()
                if (msg is TdApi.Message) {
                    val fId = when (val c = msg.content) {
                        is TdApi.MessageVideo -> c.video.video.id
                        is TdApi.MessageDocument -> c.document.document.id
                        is TdApi.MessageAudio -> c.audio.audio.id
                        is TdApi.MessageAnimation -> c.animation.animation.id
                        else -> activeFileId
                    }
                    if (fId != 0) activeFileId = fId
                }
            }

            telegramClient.fileUpdates.collect { update ->
                val f = update.file
                if (f.id == activeFileId && f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
                    _resolvedLocalPath.value = f.local.path
                    driveDao.updateLocalPath(f.id, f.local.path)
                    driveDao.updateLocalPathByUniqueId(f.remote.uniqueId, f.local.path)
                }
            }
        }
    }

    fun markOfflinePath(fileId: Int, uniqueId: String, path: String) {
        viewModelScope.launch {
            _resolvedLocalPath.value = path
            driveDao.updateLocalPath(fileId, path)
            if (uniqueId.isNotEmpty()) {
                driveDao.updateLocalPathByUniqueId(uniqueId, path)
            }
        }
    }
}

@AndroidOptIn(UnstableApi::class)
class TdlibStreamingDataSource(
    private val telegramClient: TelegramClient,
    private val chatId: Long,
    private val messageId: Long,
    private var fileId: Int,
    private val expectedSize: Long,
    private val onCompletedPath: (Int, String, String) -> Unit
) : BaseDataSource(true) {

    private var raf: RandomAccessFile? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened = false

    private fun fetchTdFile(): TdApi.File? = runBlocking {
        if (fileId == 0 && chatId != 0L && messageId != 0L) {
            val msgDef = CompletableDeferred<TdApi.Object>()
            telegramClient.send(TdApi.GetMessage(chatId, messageId)) { msgDef.complete(it) }
            val msg = msgDef.await()
            if (msg is TdApi.Message) {
                fileId = when (val c = msg.content) {
                    is TdApi.MessageVideo -> c.video.video.id
                    is TdApi.MessageDocument -> c.document.document.id
                    is TdApi.MessageAudio -> c.audio.audio.id
                    is TdApi.MessageAnimation -> c.animation.animation.id
                    else -> 0
                }
            }
        }
        if (fileId == 0) return@runBlocking null
        val def = CompletableDeferred<TdApi.Object>()
        telegramClient.send(TdApi.GetFile(fileId)) { def.complete(it) }
        def.await() as? TdApi.File
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentPosition = dataSpec.position
        val file = fetchTdFile()
        val totalSize = file?.expectedSize?.takeIf { it > 0 } ?: file?.size?.takeIf { it > 0 } ?: expectedSize

        if (file != null && !file.local.isDownloadingCompleted) {
            // শুধুমাত্র যতটুকু প্লে করার জন্য দরকার ততটুকু স্ট্রিম হবে (পুরো ফাইল অযথা ডাউনলোড কিউতে যাবে না)
            telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 4 * 1024 * 1024L, false))
        } else if (file != null && file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) {
            onCompletedPath(file.id, file.remote.uniqueId, file.local.path)
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
            val file = fetchTdFile() ?: return C.RESULT_END_OF_INPUT
            val local = file.local

            if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
                val f = File(local.path)
                if (f.exists()) {
                    onCompletedPath(file.id, file.remote.uniqueId, local.path)
                    if (raf == null) raf = RandomAccessFile(f, "r")
                    raf!!.seek(currentPosition)
                    val readBytes = raf!!.read(buffer, offset, toRead)
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
                val f = File(local.path)
                if (f.exists()) {
                    val readable = minOf(toRead.toLong(), availEnd - currentPosition).toInt()
                    if (readable > 0) {
                        if (raf == null) raf = RandomAccessFile(f, "r")
                        raf!!.seek(currentPosition)
                        val readBytes = raf!!.read(buffer, offset, readable)
                        if (readBytes > 0) {
                            currentPosition += readBytes
                            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= readBytes
                            bytesTransferred(readBytes)
                            return readBytes
                        }
                    }
                }
            }

            if (!local.isDownloadingActive || currentPosition < availStart || currentPosition >= availEnd) {
                if (lastRequestedOffset != currentPosition) {
                    telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 4 * 1024 * 1024L, false))
                    lastRequestedOffset = currentPosition
                }
            }

            try {
                Thread.sleep(80)
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

    val isStreamUrl = url.startsWith("tdstream://")
    val streamParts = remember(url) {
        if (isStreamUrl) url.removePrefix("tdstream://").split("|") else emptyList()
    }
    val chatId = streamParts.getOrNull(0)?.toLongOrNull() ?: 0L
    val messageId = streamParts.getOrNull(1)?.toLongOrNull() ?: 0L
    val telegramFileId = streamParts.getOrNull(2)?.toIntOrNull() ?: 0
    val expectedSize = streamParts.getOrNull(3)?.toLongOrNull() ?: 0L
    val fileName = if (isStreamUrl) (streamParts.getOrNull(4) ?: "Video") else url.substringAfterLast('/')

    val resolvedLocalPath by viewModel.resolvedLocalPath.collectAsState()

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isMuted by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }

    var activeGestureMode by remember { mutableStateOf(GestureMode.NONE) }
    var verticalGestureProgress by remember { mutableFloatStateOf(0f) }
    var seekOverlayText by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(url) {
        val builder = ExoPlayer.Builder(context)
        if (isStreamUrl) {
            viewModel.observeFile(chatId, messageId, telegramFileId)
            val factory = DataSource.Factory {
                TdlibStreamingDataSource(
                    telegramClient = viewModel.telegramClient,
                    chatId = chatId,
                    messageId = messageId,
                    fileId = telegramFileId,
                    expectedSize = expectedSize,
                    onCompletedPath = { fId, uId, path -> viewModel.markOfflinePath(fId, uId, path) }
                )
            }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri("tdstream://file/$telegramFileId"))
            builder.build().apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        } else {
            builder.build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(exoPlayer) {
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
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (activeGestureMode != GestureMode.SEEK) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            if (exoPlayer.duration > 0) duration = exoPlayer.duration
            delay(300)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(4000)
            controlsVisible = false
        }
    }

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
                                val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                            } else {
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
                                    // PLAYit স্টাইলে ডানে-বামে টেনে ভিডিও দেখা: 00:18[+00:03]
                                    accumulatedX += dragAmount.x
                                    val maxRangeMs = if (duration > 0) minOf(duration, 120_000L) else 60_000L
                                    val deltaMs = ((accumulatedX / size.width.toFloat()) * maxRangeMs).toLong()
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
                                    // PLAYit স্টাইলে বাম পাশে আলো বাড়ানো-কমানো
                                    val delta = -dragAmount.y / (size.height * 0.75f)
                                    val window = activity?.window
                                    val lp = window?.attributes
                                    val curBright = lp?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                    val newBright = (curBright + delta).coerceIn(0.01f, 1.0f)
                                    lp?.screenBrightness = newBright
                                    window?.attributes = lp
                                    verticalGestureProgress = newBright
                                }
                                GestureMode.VOLUME -> {
                                    // PLAYit স্টাইলে ডান পাশে সাউন্ড বাড়ানো-কমানো
                                    val delta = -dragAmount.y / (size.height * 0.75f)
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
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // বাফারিং লোডার (কোনো পার্সেন্টেজ ছাড়া)
        if (isBuffering && activeGestureMode == GestureMode.NONE) {
            CircularProgressIndicator(
                color = Color(0xFF2ECC71),
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(46.dp).align(Alignment.Center)
            )
        }

        // স্ক্রিনশট ১ ও ৪ অনুযায়ী হুবহু PLAYit Brightness ও Volume ভার্টিক্যাল ইন্ডিকেটর (কোনো বক্স ছাড়া)
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

        // স্ক্রিনশট ২ অনুযায়ী হুবহু PLAYit Horizontal Seek টেক্সট: 00:18[+00:03]
        if (activeGestureMode == GestureMode.SEEK && seekOverlayText != null) {
            Text(
                text = seekOverlayText!!,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // স্ক্রিন লক করা থাকলে শুধু বাম পাশে আনলক বাটন দেখাবে
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

        // স্ক্রিনশট ৫ অনুযায়ী হুবহু PLAYit কন্ট্রোল লেআউট
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
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            ) {
                // ১. উপরের বার (Back, Title, HDR, CC, Headphone, Playlist, 3-dot)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
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

                    // HDR with NEW badge
                    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = {
                            Toast.makeText(context, "HDR Mode Enhanced", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.HighQuality, contentDescription = "HDR", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Surface(
                            color = Color(0xFFFF5722),
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "NEW",
                                color = Color.White,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }

                    IconButton(onClick = {
                        Toast.makeText(context, "No subtitles found", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ClosedCaption, contentDescription = "CC", tint = Color.White, modifier = Modifier.size(24.dp))
                    }

                    IconButton(onClick = {
                        Toast.makeText(context, "Audio Play Mode", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Headset, contentDescription = "Audio Play", tint = Color.White, modifier = Modifier.size(22.dp))
                    }

                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Playlist", tint = Color.White, modifier = Modifier.size(26.dp))
                    }

                    IconButton(onClick = {
                        val extPath = resolvedLocalPath ?: url.takeIf { !isStreamUrl }
                        if (extPath != null) {
                            FileSharingHelper.openFileExternally(context, extPath, "video/*")
                        }
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }

                // ২. মাঝখানের বাম পাশের ফ্লোটিং আইকন (Mute এবং Lock)
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

                // ৩. মাঝখানের ডান পাশের ফ্লোটিং আইকন (Clip/Cut এবং Screen Rotation)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        onClick = {
                            Toast.makeText(context, "Frame captured", Toast.LENGTH_SHORT).show()
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

                // ৪. নিচের বার (সবুজ সিকবার এবং নিচে গোল Play/Pause, Prev, Next, Speed, Fit, PIP)
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
                        // গোল সাদা বর্ডারযুক্ত Play/Pause বাটন
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
                                val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(26.dp))
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        IconButton(
                            onClick = {
                                val totalDur = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(totalDur)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Speed বাটন
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

                        // Aspect Ratio / Fit আইকন
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

                        // PIP আইকন
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
