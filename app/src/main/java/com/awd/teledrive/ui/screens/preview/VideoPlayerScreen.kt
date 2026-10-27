package com.awd.teledrive.ui.screens.preview

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
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
import kotlin.math.abs

private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val telegramClient: TelegramClient,
    private val driveDao: DriveDao,
    driveRepository: DriveRepository
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
                // ভিডিও দেখার সময়ই পুরো ফাইলটি (limit = 0) ব্যাকগ্রাউন্ডে ডাউনলোড হতে থাকবে
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
                // পুরো ভিডিওটি ব্যাকগ্রাউন্ডে ডাউনলোড হবে (limit = 0)
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
            // ল্যাগ এড়াতে সরাসরি মেমোরি থেকে ফাইল স্ট্যাটাস পড়া হচ্ছে
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
                Thread.sleep(45)
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

    var activeMediaUrl by remember(url) { mutableStateOf(url) }

    val isStreamUrl = activeMediaUrl.startsWith("tdstream://")
    val streamParts = remember(activeMediaUrl) {
        if (isStreamUrl) activeMediaUrl.removePrefix("tdstream://").split("|") else emptyList()
    }
    val chatId = streamParts.getOrNull(0)?.toLongOrNull() ?: 0L
    val messageId = streamParts.getOrNull(1)?.toLongOrNull() ?: 0L
    val telegramFileId = streamParts.getOrNull(2)?.toIntOrNull() ?: 0
    val expectedSize = streamParts.getOrNull(3)?.toLongOrNull() ?: 0L
    val fileName = if (isStreamUrl) (streamParts.getOrNull(4) ?: "Video") else activeMediaUrl.substringAfterLast('/')

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isAudioOnlyMode by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }

    var activeGestureMode by remember { mutableStateOf(GestureMode.NONE) }
    var verticalGestureProgress by remember { mutableFloatStateOf(0f) }
    var seekOverlayText by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
    }

    LaunchedEffect(activeMediaUrl) {
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

    LaunchedEffect(controlsVisible, isPlaying, isAudioOnlyMode) {
        if (controlsVisible && isPlaying && !isAudioOnlyMode) {
            delay(3500)
            controlsVisible = false
        }
    }

    val skipByMs: (Long) -> Unit = { deltaMs ->
        val totalDur = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val next = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, totalDur)
        exoPlayer.seekTo(next)
        currentPosition = next
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            skipByMs(-10_000L)
                        } else {
                            skipByMs(10_000L)
                        }
                    }
                )
            }
            .pointerInput(duration) {
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
    ) {
        if (!isAudioOnlyMode) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // অডিও প্লে মোড (Audio Mode UI)
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF2ECC71).copy(alpha = 0.18f),
                    modifier = Modifier.size(120.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFF2ECC71),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        if (isBuffering && activeGestureMode == GestureMode.NONE) {
            CircularProgressIndicator(
                color = Color(0xFF2ECC71),
                strokeWidth = 3.5.dp,
                modifier = Modifier.size(46.dp).align(Alignment.Center)
            )
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

        // টেনে ভিডিও দেখার সময় টেক্সট (00:18[+00:03])
        if (activeGestureMode == GestureMode.SEEK && seekOverlayText != null) {
            Text(
                text = seekOverlayText!!,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
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
                // উপরের বার: Back, নাম, Audio Play মোড এবং Screen Rotate
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
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

                    // অডিও প্লে অপশন
                    IconButton(
                        onClick = { isAudioOnlyMode = !isAudioOnlyMode }
                    ) {
                        Icon(
                            imageVector = if (isAudioOnlyMode) Icons.Default.Videocam else Icons.Default.Headset,
                            contentDescription = "Audio Play",
                            tint = if (isAudioOnlyMode) Color(0xFF2ECC71) else Color.White
                        )
                    }

                    // রোটেশন বাটন
                    IconButton(
                        onClick = {
                            isLandscape = !isLandscape
                            activity?.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Rotate",
                            tint = Color.White
                        )
                    }
                }

                // নিচের কন্ট্রোল বার: সিকবার + ভিডিও পরিবর্তন (Prev/Next) + ১০ সেকেন্ড স্কিপ + Play/Pause
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 16.dp)
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
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // আগের ভিডিও পরিবর্তন বাটন
                        IconButton(
                            onClick = {
                                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = false)?.let { prevUrl ->
                                    activeMediaUrl = prevUrl
                                }
                            }
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Video", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        // ১০ সেকেন্ড পেছনে স্কিপ
                        IconButton(
                            onClick = { skipByMs(-10_000L) }
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        // Play / Pause বাটন
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(BorderStroke(2.dp, Color.White), CircleShape)
                                .clickable {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // ১০ সেকেন্ড সামনে স্কিপ
                        IconButton(
                            onClick = { skipByMs(10_000L) }
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        // পরের ভিডিও পরিবর্তন বাটন
                        IconButton(
                            onClick = {
                                viewModel.getAdjacentMediaUrl(activeMediaUrl, next = true)?.let { nextUrl ->
                                    activeMediaUrl = nextUrl
                                }
                            }
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next Video", tint = Color.White, modifier = Modifier.size(30.dp))
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
