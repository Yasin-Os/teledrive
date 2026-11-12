package com.awd.teledrive.ui.screens.preview

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.components.getFileIconAndColor
import com.awd.teledrive.ui.theme.AccentCyan
import com.awd.teledrive.ui.theme.DeleteRed
import com.awd.teledrive.ui.theme.PreviewBackground
import com.awd.teledrive.ui.theme.PreviewPanelDark
import com.awd.teledrive.ui.theme.StarGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val decryptedPaths by viewModel.decryptedPaths.collectAsState()
    val isDecrypting by viewModel.isDecrypting.collectAsState()
    val isOpening by viewModel.isOpening.collectAsState()

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    } else {
        val initialPage = remember { viewModel.getInitialIndex().coerceIn(0, (items.size - 1).coerceAtLeast(0)) }
        val pagerState = rememberPagerState(initialPage = initialPage) { items.size }

        PreviewPager(
            items = items,
            transfers = transfers,
            decryptedPaths = decryptedPaths,
            isDecrypting = isDecrypting,
            isOpening = isOpening,
            pagerState = pagerState,
            onBack = onBack,
            onOpenPlayer = onOpenPlayer,
            onOpenPdf = onOpenPdf,
            onOpenText = onOpenText,
            onToggleStarred = viewModel::toggleStarred,
            onSaveToDevice = viewModel::saveToDevice,
            onAutoDownload = viewModel::autoDownloadForPreview,
            onLoadFile = viewModel::downloadForPreview,
            onDeleteItem = { file ->
                viewModel.deleteItem(file)
                onBack()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewPager(
    items: List<DriveItem.File>,
    transfers: Map<String, TransferInfo>,
    decryptedPaths: Map<Long, String>,
    isDecrypting: Map<Long, Boolean>,
    isOpening: Map<Long, Boolean>,
    pagerState: PagerState,
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    onToggleStarred: (DriveItem.File) -> Unit,
    onSaveToDevice: (DriveItem.File) -> Unit,
    onAutoDownload: (DriveItem.File) -> Unit,
    onLoadFile: (DriveItem.File) -> Unit,
    onDeleteItem: (DriveItem.File) -> Unit
) {
    val context = LocalContext.current
    if (items.isEmpty() || pagerState.currentPage >= items.size) return
    val currentFile = items[pagerState.currentPage]

    var showGalleryBars by remember { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingImagePath by remember { mutableStateOf<String?>(null) }
    var editedOverridePath by remember(currentFile.id) { mutableStateOf<String?>(null) }

    // পেজ পরিবর্তন হলে জুম রিসেট ও পাশের ছবি প্রি-লোড করা হবে যাতে সোয়াইপ সুপার ফাস্ট হয়
    LaunchedEffect(pagerState.currentPage, items.size) {
        isCurrentPageZoomed = false
        if (items.isNotEmpty() && pagerState.currentPage < items.size) {
            onAutoDownload(items[pagerState.currentPage])
            items.getOrNull(pagerState.currentPage + 1)?.let { next ->
                if (next.mimeType.startsWith("image/")) onAutoDownload(next)
            }
            items.getOrNull(pagerState.currentPage - 1)?.let { prev ->
                if (prev.mimeType.startsWith("image/")) onAutoDownload(prev)
            }
        }
    }

    val validLocalPath = editedOverridePath ?: currentFile.localPath?.takeIf { File(it).exists() }
    val currentDisplayPath = if (currentFile.isEncrypted) (editedOverridePath ?: decryptedPaths[currentFile.id]) else validLocalPath

    if (editingImagePath != null) {
        PhotoEditorScreen(
            imagePath = editingImagePath!!,
            fileName = currentFile.name,
            onCancel = { editingImagePath = null },
            onSaveEdited = { savedFile ->
                editedOverridePath = savedFile.absolutePath
                editingImagePath = null
            }
        )
        return
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteItem(currentFile)
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.file_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.name_label, currentFile.name), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.size_label, FileUtils.formatSize(currentFile.size)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.type_label, currentFile.mimeType), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isCurrentPageZoomed,
            beyondViewportPageCount = 1
        ) { pageIndex ->
            val file = items[pageIndex]
            val transfer = transfers[file.remoteUniqueId] ?: transfers.values.find { it.fileId == file.telegramFileId }
            val pageLocal = if (file.id == currentFile.id && editedOverridePath != null) editedOverridePath else file.localPath?.takeIf { File(it).exists() }
            val displayPath = if (file.isEncrypted) (if (file.id == currentFile.id && editedOverridePath != null) editedOverridePath else decryptedPaths[file.id]) else pageLocal

            val decrypting = isDecrypting[file.id] ?: false
            val opening = isOpening[file.id] ?: false

            if (file.isSplit) {
                SplitFilePreviewPlaceholder(file)
            } else {
                PreviewContent(
                    file = file,
                    displayPath = displayPath,
                    transfer = transfer,
                    isDecrypting = decrypting,
                    isOpening = opening,
                    isCurrentPage = pageIndex == pagerState.currentPage,
                    onOpenPlayer = onOpenPlayer,
                    onOpenPdf = onOpenPdf,
                    onOpenText = onOpenText,
                    onToggleBars = { showGalleryBars = !showGalleryBars },
                    onZoomChanged = { zoomed ->
                        if (pageIndex == pagerState.currentPage) {
                            isCurrentPageZoomed = zoomed
                        }
                    },
                    onLoadFile = { onLoadFile(file) }
                )
            }
        }

        AnimatedVisibility(
            visible = showGalleryBars,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentFile.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${items.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = showGalleryBars,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GalleryActionButton(
                        icon = Icons.Default.Share,
                        label = stringResource(R.string.share),
                        enabled = currentDisplayPath != null,
                        onClick = {
                            currentDisplayPath?.let {
                                FileSharingHelper.shareFile(context, it, currentFile.mimeType)
                            }
                        }
                    )

                    if (currentFile.mimeType.startsWith("image/")) {
                        GalleryActionButton(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.edit),
                            enabled = currentDisplayPath != null,
                            onClick = {
                                editingImagePath = currentDisplayPath
                            }
                        )
                    }

                    GalleryActionButton(
                        icon = if (currentFile.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                        label = stringResource(R.string.favorite),
                        tint = if (currentFile.isStarred) StarGold else Color.White,
                        onClick = { onToggleStarred(currentFile) }
                    )

                    GalleryActionButton(
                        icon = Icons.Default.Download,
                        label = stringResource(R.string.save),
                        enabled = currentDisplayPath != null,
                        onClick = {
                            onSaveToDevice(currentFile)
                        }
                    )

                    GalleryActionButton(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.delete),
                        tint = DeleteRed,
                        onClick = { showDeleteConfirm = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp
        )
    }
}

@Composable
fun PhotoEditorScreen(
    imagePath: String,
    fileName: String,
    onCancel: () -> Unit,
    onSaveEdited: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Preview only ever displays the image at screen size, so decode a sampled
    // bitmap that fits the screen instead of the full-resolution file. The
    // full-resolution bitmap is decoded only when saving (see the Save button).
    val displayMetrics = context.resources.displayMetrics
    val previewSourceBitmap = remember(imagePath) {
        decodeSampledBitmap(
            imagePath,
            maxWidth = displayMetrics.widthPixels,
            maxHeight = displayMetrics.heightPixels
        )
    }

    var activeTab by remember { mutableIntStateOf(0) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }

    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }

    // Slider ticks move the thumb immediately, but the bitmap re-render waits
    // until the user pauses ~100 ms, so dragging a slider no longer renders
    // a full bitmap on every tick.
    var debouncedBrightness by remember { mutableFloatStateOf(brightness) }
    var debouncedContrast by remember { mutableFloatStateOf(contrast) }
    var debouncedSaturation by remember { mutableFloatStateOf(saturation) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(brightness, contrast, saturation) }
            .debounce(100)
            .collect { (b, c, s) ->
                debouncedBrightness = b
                debouncedContrast = c
                debouncedSaturation = s
            }
    }

    var selectedFilter by remember { mutableStateOf("Original") }

    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val previewBitmap = remember(previewSourceBitmap, rotationDegrees, flipHorizontal, debouncedBrightness, debouncedContrast, debouncedSaturation, selectedFilter) {
        previewSourceBitmap?.let { src ->
            renderEditedBitmap(
                source = src,
                rotation = rotationDegrees,
                flipH = flipHorizontal,
                brightness = debouncedBrightness,
                contrast = debouncedContrast,
                saturation = debouncedSaturation,
                filterName = selectedFilter,
                cropRect = null
            )
        }
    }

    if (previewSourceBitmap == null) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PreviewBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Text(
                text = "Photo Studio",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        // Full resolution only when saving: decode the original
                        // file here on IO instead of holding it in memory
                        // for the whole editing session.
                        val fullBitmap = BitmapFactory.decodeFile(imagePath)
                        if (fullBitmap == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show()
                                onCancel()
                            }
                            return@launch
                        }
                        try {
                            val finalBmp = renderEditedBitmap(
                                source = fullBitmap,
                                rotation = rotationDegrees,
                                flipH = flipHorizontal,
                                brightness = brightness,
                                contrast = contrast,
                                saturation = saturation,
                                filterName = selectedFilter,
                                cropRect = Rect(cropLeft, cropTop, cropRight, cropBottom)
                            )
                            val outFile = saveBitmapToGalleryAndCache(context, finalBmp, "Edited_$fileName")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                                if (outFile != null) onSaveEdited(outFile) else onCancel()
                            }
                        } finally {
                            fullBitmap.recycle()
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.save))
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(20.dp)
                .onSizeChanged { boxSize = it }
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (activeTab == 0) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = change.position.x / w
                                val ny = change.position.y / h
                                val dx = dragAmount.x / w
                                val dy = dragAmount.y / h

                                val midX = (cropLeft + cropRight) / 2f
                                val midY = (cropTop + cropBottom) / 2f

                                if (nx < midX) {
                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - 0.15f)
                                } else {
                                    cropRight = (cropRight + dx).coerceIn(cropLeft + 0.15f, 1f)
                                }
                                if (ny < midY) {
                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - 0.15f)
                                } else {
                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + 0.15f, 1f)
                                }
                            }
                        }
                ) {
                    val leftPx = cropLeft * size.width
                    val topPx = cropTop * size.height
                    val rightPx = cropRight * size.width
                    val bottomPx = cropBottom * size.height
                    val rectW = rightPx - leftPx
                    val rectH = bottomPx - topPx

                    drawRect(
                        color = AccentCyan,
                        topLeft = Offset(leftPx, topPx),
                        size = Size(rectW, rectH),
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(leftPx + rectW / 3f, topPx),
                        end = Offset(leftPx + rectW / 3f, bottomPx),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(leftPx + 2f * rectW / 3f, topPx),
                        end = Offset(leftPx + 2f * rectW / 3f, bottomPx),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(leftPx, topPx + rectH / 3f),
                        end = Offset(rightPx, topPx + rectH / 3f),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(leftPx, topPx + 2f * rectH / 3f),
                        end = Offset(rightPx, topPx + 2f * rectH / 3f),
                        strokeWidth = 1.dp.toPx()
                    )

                    val r = 6.dp.toPx()
                    drawCircle(Color.White, r, Offset(leftPx, topPx))
                    drawCircle(Color.White, r, Offset(rightPx, topPx))
                    drawCircle(Color.White, r, Offset(leftPx, bottomPx))
                    drawCircle(Color.White, r, Offset(rightPx, bottomPx))
                }
            }
        }

        Surface(
            color = PreviewPanelDark,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                when (activeTab) {
                    0 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f },
                                label = { Text(stringResource(R.string.aspect_full)) }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { cropLeft = 0.15f; cropTop = 0.15f; cropRight = 0.85f; cropBottom = 0.85f },
                                label = { Text("1:1") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { cropLeft = 0.1f; cropTop = 0.2f; cropRight = 0.9f; cropBottom = 0.8f },
                                label = { Text("4:3") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { cropLeft = 0.05f; cropTop = 0.25f; cropRight = 0.95f; cropBottom = 0.75f },
                                label = { Text("16:9") }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            OutlinedButton(onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.rotate_90))
                            }
                            OutlinedButton(onClick = { flipHorizontal = !flipHorizontal }) {
                                Icon(Icons.Default.Flip, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.flip))
                            }
                        }
                    }
                    1 -> {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.brightness), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = brightness,
                                    onValueChange = { brightness = it },
                                    valueRange = -80f..80f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.contrast), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = contrast,
                                    onValueChange = { contrast = it },
                                    valueRange = 0.5f..1.6f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.saturation), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = saturation,
                                    onValueChange = { saturation = it },
                                    valueRange = 0f..2f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    2 -> {
                        val filters = listOf("Original", "Vivid", "Warm", "Cool", "B&W")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(filters) { fName ->
                                FilterChip(
                                    selected = selectedFilter == fName,
                                    onClick = { selectedFilter = fName },
                                    label = { Text(fName) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GalleryActionButton(
                        icon = Icons.Default.Crop,
                        label = stringResource(R.string.crop_rotate),
                        tint = if (activeTab == 0) AccentCyan else Color.White,
                        onClick = { activeTab = 0 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.Tune,
                        label = stringResource(R.string.adjust),
                        tint = if (activeTab == 1) AccentCyan else Color.White,
                        onClick = { activeTab = 1 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.AutoAwesome,
                        label = stringResource(R.string.filters),
                        tint = if (activeTab == 2) AccentCyan else Color.White,
                        onClick = { activeTab = 2 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.reset),
                        onClick = {
                            rotationDegrees = 0f
                            flipHorizontal = false
                            brightness = 0f
                            contrast = 1f
                            saturation = 1f
                            selectedFilter = "Original"
                            cropLeft = 0.05f; cropTop = 0.05f; cropRight = 0.95f; cropBottom = 0.95f
                        }
                    )
                }
            }
        }
    }
}

/**
 * Decodes [imagePath] sampled down with [BitmapFactory.Options.inSampleSize]
 * so its longest side fits [maxWidth]/[maxHeight] (screen size). Returns null
 * if the file cannot be decoded.
 */
private fun decodeSampledBitmap(imagePath: String, maxWidth: Int, maxHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(imagePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val reqWidth = maxWidth.coerceAtLeast(1)
    val reqHeight = maxHeight.coerceAtLeast(1)
    val ratio = maxOf(
        bounds.outWidth / reqWidth.toFloat(),
        bounds.outHeight / reqHeight.toFloat()
    )
    var inSampleSize = 1
    while (inSampleSize * 2 <= ratio) inSampleSize *= 2
    return BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
    })
}

private fun renderEditedBitmap(
    source: Bitmap,
    rotation: Float,
    flipH: Boolean,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    filterName: String,
    cropRect: Rect?
): Bitmap {
    val matrix = Matrix().apply {
        if (rotation != 0f) postRotate(rotation)
        if (flipH) postScale(-1f, 1f)
    }
    val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

    val cropped = if (cropRect != null) {
        val x = (cropRect.left * transformed.width).toInt().coerceIn(0, transformed.width - 10)
        val y = (cropRect.top * transformed.height).toInt().coerceIn(0, transformed.height - 10)
        val w = ((cropRect.right - cropRect.left) * transformed.width).toInt().coerceIn(10, transformed.width - x)
        val h = ((cropRect.bottom - cropRect.top) * transformed.height).toInt().coerceIn(10, transformed.height - y)
        Bitmap.createBitmap(transformed, x, y, w, h)
    } else transformed

    val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    val cm = ColorMatrix()

    val effSat = when (filterName) {
        "Vivid" -> saturation * 1.35f
        "B&W" -> 0f
        else -> saturation
    }
    cm.setSaturation(effSat)

    val c = if (filterName == "Vivid") contrast * 1.15f else contrast
    val rScale = if (filterName == "Warm") c * 1.1f else c
    val bScale = if (filterName == "Cool") c * 1.12f else c
    val translate = (-0.5f * c + 0.5f) * 255f + brightness

    val contrastMatrix = ColorMatrix(
        floatArrayOf(
            rScale, 0f, 0f, 0f, translate,
            0f, c, 0f, 0f, translate,
            0f, 0f, bScale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    cm.postConcat(contrastMatrix)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(cm)
    }
    canvas.drawBitmap(cropped, 0f, 0f, paint)
    return out
}

private fun saveBitmapToGalleryAndCache(context: Context, bitmap: Bitmap, fileName: String): File? {
    return try {
        val cacheOut = File(context.cacheDir, "edited_${System.currentTimeMillis()}_$fileName")
        FileOutputStream(cacheOut).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
        }
        cacheOut
    } catch (e: Exception) {
        null
    }
}

@Composable
fun PreviewContent(
    file: DriveItem.File,
    displayPath: String?,
    transfer: TransferInfo?,
    isDecrypting: Boolean,
    isOpening: Boolean,
    isCurrentPage: Boolean,
    onOpenPlayer: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenText: (String) -> Unit,
    onToggleBars: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onLoadFile: () -> Unit
) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    val isStreamableMedia = !file.isEncrypted && (file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/"))

    var isMediaRendering by remember(displayPath) { mutableStateOf(false) }
    val isWaitingForDecryption = file.isEncrypted && file.localPath != null && displayPath == null
    val isTransferring = transfer != null && (transfer.status == TransferRepository.Status.DOWNLOADING || transfer.status == TransferRepository.Status.UPLOADING)
    val isLoading = isTransferring || isDecrypting || isOpening || isMediaRendering || isWaitingForDecryption

    var scale by remember(file.id) { mutableFloatStateOf(1f) }
    var offset by remember(file.id) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // অন্য পেজে সোয়াইপ হয়ে গেলে জুম রিসেট করে দেবে
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    val triggerStreamOrPlay = {
        if (displayPath != null && File(displayPath).exists()) {
            onOpenPlayer(displayPath)
        } else {
            onLoadFile()
            val streamToken = "tdstream://${file.parentChatId}|${file.id}|${file.telegramFileId}|${file.size}|${file.name}"
            onOpenPlayer(streamToken)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(file.id) {
                detectTapGestures(
                    onTap = { onToggleBars() },
                    onDoubleTap = { tapOffset ->
                        if (isImage) {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomChanged(false)
                            } else {
                                val targetScale = 2.5f
                                val centerX = containerSize.width / 2f
                                val centerY = containerSize.height / 2f
                                val maxX = (containerSize.width * (targetScale - 1f)) / 2f
                                val maxY = (containerSize.height * (targetScale - 1f)) / 2f
                                offset = Offset(
                                    x = ((centerX - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxX, maxX),
                                    y = ((centerY - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxY, maxY)
                                )
                                scale = targetScale
                                onZoomChanged(true)
                            }
                        }
                    }
                )
            }
            .pointerInput(file.id) {
                if (!isImage) return@pointerInput
                // স্মার্ট জেসচার ডিটেক্টর: জুম না থাকলে ১-আঙুলের সোয়াইপ HorizontalPager-কে ছেড়ে দেয়!
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            val maxX = (size.width * (newScale - 1f)) / 2f
                            val maxY = (size.height * (newScale - 1f)) / 2f

                            scale = newScale
                            if (newScale > 1.04f) {
                                offset = Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                                onZoomChanged(true)
                            } else {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomChanged(false)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pointerCount == 1 && scale > 1.04f) {
                            val panChange = event.calculatePan()
                            val maxX = (size.width * (scale - 1f)) / 2f
                            val maxY = (size.height * (scale - 1f)) / 2f
                            val nextX = offset.x + panChange.x
                            val nextY = offset.y + panChange.y

                            // জুম অবস্থায় ছবির একদম কিনারায় পৌঁছে গেলে পেজারকে সোয়াইপ করতে দেবে
                            val hittingHorizontalEdge = (nextX > maxX + 12f && panChange.x > 0) ||
                                    (nextX < -maxX - 12f && panChange.x < 0)

                            if (hittingHorizontalEdge && abs(panChange.x) > abs(panChange.y)) {
                                onZoomChanged(false)
                            } else {
                                offset = Offset(
                                    x = nextX.coerceIn(-maxX, maxX),
                                    y = nextY.coerceIn(-maxY, maxY)
                                )
                                onZoomChanged(true)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (scale <= 1.04f) {
                            // ১x অবস্থায় কোনো ইভেন্ট কনজিউম করা হবে না যাতে ডানে-বামে সোয়াইপ ১০০% স্মুথ কাজ করে
                            if (scale != 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            }
                            onZoomChanged(false)
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isImage) {
            val modelToShow = displayPath ?: file.thumbnailPath
            if (modelToShow != null) {
                AsyncImage(
                    model = modelToShow,
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit,
                    onLoading = { isMediaRendering = true },
                    onSuccess = { isMediaRendering = false },
                    onError = { isMediaRendering = false }
                )
            }
            if (displayPath == null && isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            val (icon, color) = getFileIconAndColor(file)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(220.dp)
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(110.dp),
                        tint = color
                    )
                    if (isStreamableMedia) {
                        Surface(
                            onClick = triggerStreamOrPlay,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(68.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = FileUtils.formatSize(file.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.65f)
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (isStreamableMedia) {
                    Button(
                        onClick = triggerStreamOrPlay,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.play_media))
                    }
                } else if (displayPath != null) {
                    val isInternalDoc = file.mimeType == "application/pdf" || file.mimeType.startsWith("text/") || file.name.endsWith(".txt", true)
                    Button(
                        onClick = {
                            if (isInternalDoc) {
                                if (file.mimeType == "application/pdf") onOpenPdf(displayPath)
                                else onOpenText(displayPath)
                            } else {
                                FileSharingHelper.openFileExternally(context, displayPath, file.mimeType)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.open_media))
                    }
                } else {
                    OutlinedButton(
                        onClick = onLoadFile,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isLoading) stringResource(R.string.downloading_percentage, ((transfer?.progress ?: 0f) * 100).toInt()) else stringResource(R.string.download_file))
                    }
                }
            }
        }
    }
}

@Composable
fun SplitFilePreviewPlaceholder(file: DriveItem.File) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.file_too_large),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
        }
    }
}


