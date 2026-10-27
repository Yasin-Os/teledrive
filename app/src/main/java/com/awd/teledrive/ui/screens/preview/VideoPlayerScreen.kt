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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val telegramClient: TelegramClient,
    private val driveDao: DriveDao
) : ViewModel() {

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()

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
                if (f.id == activeFileId) {
                    val total = if (f.expectedSize > 0) f.expectedSize else f.size
                    val dl = f.local.downloadedSize
                    if (total > 0) {
                        _downloadProgress.value = ((dl.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                    }
                    if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
                        _downloadProgress.value = 100
                        _resolvedLocalPath.value = f.local.path
                        driveDao.updateLocalPath(f.id, f.local.path)
                        driveDao.updateLocalPathByUniqueId(f.remote.uniqueId, f.local.path)
                    }
                }
            }
        }
    }

    fun markOfflinePath(fileId: Int, uniqueId: String, path: String) {
        viewModelScope.launch {
            _resolvedLocalPath.value = path
            _downloadProgress.value = 100
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
            telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 0, false))
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

            if (!local.isDownloadingActive || currentPosition < availStart || (currentPosition - availEnd) > 2 * 1024 * 1024L) {
                if (lastRequestedOffset != currentPosition) {
                    telegramClient.send(TdApi.DownloadFile(file.id, 32, currentPosition, 0, false))
                    lastRequestedOffset = currentPosition
                }
            }

            try {
                Thread.sleep(100)
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

    val downloadProgress by viewModel.downloadProgress.collectAsState()
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

    var gestureOverlayText by remember { mutableStateOf<String?>(null) }
    var gestureOverlayProgress by remember { mutableFloatStateOf(-1f) }
    var isBrightnessGesture by remember { mutableStateOf(false) }

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
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0) duration = exoPlayer.duration
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(4000)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureOverlayText) {
        if (gestureOverlayText != null) {
            delay(1000)
            gestureOverlayText = null
            gestureOverlayProgress = -1f
        }
    }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            if (offset.x < size.width / 2f) {
                                val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                                gestureOverlayProgress = -1f
                                gestureOverlayText = "⏪ -10s"
                            } else {
                                val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                                exoPlayer.seekTo(next)
                                currentPosition = next
                                gestureOverlayProgress = -1f
                                gestureOverlayText = "⏩ +10s"
                            }
                        }
                    }
                )
            }
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val isLeft = change.position.x < size.width / 2f
                        val delta = -dragAmount / size.height.toFloat()

                        if (isLeft) {
                            isBrightnessGesture = true
                            val window = activity?.window
                            val lp = window?.attributes
                            val currentBright = lp?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                            val newBright = (currentBright + delta).coerceIn(0.05f, 1.0f)
                            lp?.screenBrightness = newBright
                            window?.attributes = lp
                            gestureOverlayProgress = newBright
                            gestureOverlayText = "Brightness ${(newBright * 100).toInt()}%"
                        } else {
                            isBrightnessGesture = false
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val newVolFloat = ((curVol.toFloat() / maxVol) + delta).coerceIn(0f, 1f)
                            val newVol = (newVolFloat * maxVol).toInt()
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            gestureOverlayProgress = newVolFloat
                            gestureOverlayText = "Volume ${(newVolFloat * 100).toInt()}%"
                        }
                    }
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
                    playerViewRef = this
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Buffering / Streaming Progress Indicator
        if (isBuffering) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFFF6D00),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isStreamUrl && downloadProgress in 1..99) "Streaming... $downloadProgress%" else "Loading...",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Gesture Feedback Pill (Brightness / Volume / Seek)
        AnimatedVisibility(
            visible = gestureOverlayText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (gestureOverlayProgress >= 0f) {
                        Icon(
                            imageVector = if (isBrightnessGesture) Icons.Default.BrightnessHigh else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { gestureOverlayProgress },
                            color = Color(0xFFFF6D00),
                            modifier = Modifier.width(120.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = gestureOverlayText ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Screen Lock Overlay
        if (isLocked && controlsVisible) {
            Surface(
                onClick = { isLocked = false },
                shape = CircleShape,
                color = Color(0xFFFF6D00),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = Color.White)
                }
            }
        }

        // PLAYit Full Controls Overlay
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
                                Color.Black.copy(alpha = 0.7f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val isOffline = !isStreamUrl || resolvedLocalPath != null || downloadProgress >= 100
                        Text(
                            text = if (isOffline) "PLAYit • Offline Ready" else "PLAYit • Live Streaming ($downloadProgress%)",
                            color = if (isOffline) Color(0xFF4CAF50) else Color(0xFFFF6D00),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    val externalPath = resolvedLocalPath ?: url.takeIf { !isStreamUrl }
                    if (externalPath != null) {
                        IconButton(onClick = {
                            FileSharingHelper.openFileExternally(context, externalPath, "video/*")
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open Externally", tint = Color.White)
                        }
                    }
                }

                // Center Play / Pause / Seek Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            val next = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(next)
                            currentPosition = next
                        },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }

                    Surface(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        shape = CircleShape,
                        color = Color(0xFFFF6D00),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            val next = (exoPlayer.currentPosition + 10_000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                            exoPlayer.seekTo(next)
                            currentPosition = next
                        },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }
                }

                // Bottom PLAYit Control Bar
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
                            style = MaterialTheme.typography.labelMedium
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
                                thumbColor = Color(0xFFFF6D00),
                                activeTrackColor = Color(0xFFFF6D00),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        )
                        Text(
                            text = formatDuration(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // PLAYit Quick Action Tools Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayItToolButton(
                            icon = Icons.Default.LockOpen,
                            label = "Lock",
                            onClick = { isLocked = true }
                        )
                        PlayItToolButton(
                            icon = Icons.Default.Speed,
                            label = "${playbackSpeed}x",
                            onClick = {
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
                        )
                        PlayItToolButton(
                            icon = Icons.Default.AspectRatio,
                            label = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                else -> "Fit"
                            },
                            onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }
                        )
                        PlayItToolButton(
                            icon = Icons.Default.ScreenRotation,
                            label = "Rotate",
                            onClick = {
                                isLandscape = !isLandscape
                                activity?.requestedOrientation = if (isLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            }
                        )
                        PlayItToolButton(
                            icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            label = if (isMuted) "Muted" else "Audio",
                            onClick = {
                                isMuted = !isMuted
                                exoPlayer.volume = if (isMuted) 0f else 1f
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayItToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.12f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
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
