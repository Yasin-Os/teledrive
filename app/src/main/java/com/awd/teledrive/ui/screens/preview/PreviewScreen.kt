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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.awd.teledrive.data.model.TransferInfo
import com.awd.teledrive.data.repository.TransferRepository
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

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
        val initialPage = remember { viewModel.getInitialIndex() }
        val pagerState = rememberPagerState(initialPage = initialPage) { items.size }

        LaunchedEffect(items) {
            if (items.isNotEmpty()) {
                val currentFileId = items.getOrNull(pagerState.currentPage)?.id
                if (currentFileId != null) {
                    val newIndex = items.indexOfFirst { it.id == currentFileId }
                    if (newIndex != -1 && newIndex != pagerState.currentPage) {
                        pagerState.scrollToPage(newIndex)
                    }
                }
            }
        }

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
    var isZoomedIn by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingImagePath by remember { mutableStateOf<String?>(null) }
    var editedOverridePath by remember(currentFile.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState.currentPage, items.size) {
        if (items.isNotEmpty() && pagerState.currentPage < items.size) {
            if (!pagerState.isScrollInProgress) {
                onAutoDownload(items[pagerState.currentPage])
            }
        }
    }

    val validLocalPath = editedOverridePath ?: currentFile.localPath?.takeIf { File(it).exists() }
    val currentDisplayPath = if (currentFile.isEncrypted) (editedOverridePath ?: decryptedPaths[currentFile.id]) else validLocalPath

    // বিল্ট-ইন ফটো ক্রপ ও এডিটর চালু থাকলে সেটি দেখাবে
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
                    Text(stringResource(R.string.size_label, formatSize(currentFile.size)), style = MaterialTheme.typography.bodyMedium)
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
            userScrollEnabled = !isZoomedIn,
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
                    onOpenPlayer = onOpenPlayer,
                    onOpenPdf = onOpenPdf,
                    onOpenText = onOpenText,
                    onToggleBars = { showGalleryBars = !showGalleryBars },
                    onZoomChanged = { zoomed -> isZoomedIn = zoomed },
                    onLoadFile = { onLoadFile(file) }
                )
            }
        }

        // উপরের গ্যালারি বার
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

        // নিচের ফোনের গ্যালারি স্টাইল অ্যাকশন টুলবার (Share, Edit/Crop, Star, Save, Delete)
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
                        label = "Share",
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
                            label = "Edit",
                            enabled = currentDisplayPath != null,
                            onClick = {
                                editingImagePath = currentDisplayPath
                            }
                        )
                    }

                    GalleryActionButton(
                        icon = if (currentFile.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                        label = "Favorite",
                        tint = if (currentFile.isStarred) Color(0xFFFFD54F) else Color.White,
                        onClick = { onToggleStarred(currentFile) }
                    )

                    GalleryActionButton(
                        icon = Icons.Default.Download,
                        label = "Save",
                        enabled = currentDisplayPath != null,
                        onClick = {
                            onSaveToDevice(currentFile)
                        }
                    )

                    GalleryActionButton(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        tint = Color(0xFFEF5350),
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

// বিল্ট-ইন ফটো ক্রপ ও বেসিক এডিটর স্ক্রিন
@Composable
fun PhotoEditorScreen(
    imagePath: String,
    fileName: String,
    onCancel: () -> Unit,
    onSaveEdited: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val originalBitmap = remember(imagePath) {
        BitmapFactory.decodeFile(imagePath)
    }

    var activeTab by remember { mutableIntStateOf(0) } // 0=Crop, 1=Adjust, 2=Filters
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }

    var brightness by remember { mutableFloatStateOf(0f) } // -100..100
    var contrast by remember { mutableFloatStateOf(1f) }   // 0.5..1.8
    var saturation by remember { mutableFloatStateOf(1f) } // 0..2

    var selectedFilter by remember { mutableStateOf("Original") }

    // নরমালাইজড ক্রপ বক্স (0f..1f)
    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val previewBitmap = remember(originalBitmap, rotationDegrees, flipHorizontal, brightness, contrast, saturation, selectedFilter) {
        originalBitmap?.let { src ->
            renderEditedBitmap(
                source = src,
                rotation = rotationDegrees,
                flipH = flipHorizontal,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                filterName = selectedFilter,
                cropRect = null
            )
        }
    }

    if (originalBitmap == null) {
        LaunchedEffect(Unit) { onCancel() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Editor Bar
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
                        val finalBmp = renderEditedBitmap(
                            source = originalBitmap,
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
                    }
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save")
            }
        }

        // Image Preview + Interactive Crop Box
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

                    // Crop Frame Border
                    drawRect(
                        color = Color(0xFF00E5FF),
                        topLeft = Offset(leftPx, topPx),
                        size = Size(rectW, rectH),
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    // Rule of Thirds Grid Lines
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

                    // 4 Corner Handles
                    val r = 6.dp.toPx()
                    drawCircle(Color.White, r, Offset(leftPx, topPx))
                    drawCircle(Color.White, r, Offset(rightPx, topPx))
                    drawCircle(Color.White, r, Offset(leftPx, bottomPx))
                    drawCircle(Color.White, r, Offset(rightPx, bottomPx))
                }
            }
        }

        // Editor Controls Area
        Surface(
            color = Color(0xFF141923),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                when (activeTab) {
                    0 -> {
                        // Crop & Rotate Tools
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { cropLeft = 0f; cropTop = 0f; cropRight = 1f; cropBottom = 1f },
                                label = { Text("Full") }
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
                                Text("Rotate 90°")
                            }
                            OutlinedButton(onClick = { flipHorizontal = !flipHorizontal }) {
                                Icon(Icons.Default.Flip, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Flip")
                            }
                        }
                    }
                    1 -> {
                        // Brightness, Contrast, Saturation Sliders
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Brightness", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = brightness,
                                    onValueChange = { brightness = it },
                                    valueRange = -80f..80f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Contrast", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = contrast,
                                    onValueChange = { contrast = it },
                                    valueRange = 0.5f..1.6f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Saturation", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
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
                        // Color Filters
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

                // Bottom Mode Selector Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GalleryActionButton(
                        icon = Icons.Default.Crop,
                        label = "Crop & Rotate",
                        tint = if (activeTab == 0) Color(0xFF00E5FF) else Color.White,
                        onClick = { activeTab = 0 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.Tune,
                        label = "Adjust",
                        tint = if (activeTab == 1) Color(0xFF00E5FF) else Color.White,
                        onClick = { activeTab = 1 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "Filters",
                        tint = if (activeTab == 2) Color(0xFF00E5FF) else Color.White,
                        onClick = { activeTab = 2 }
                    )
                    GalleryActionButton(
                        icon = Icons.Default.Refresh,
                        label = "Reset",
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
            .pointerInput(file.id) {
                detectTapGestures(
                    onTap = { onToggleBars() },
                    onDoubleTap = {
                        if (isImage) {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomChanged(false)
                            } else {
                                scale = 2.5f
                                onZoomChanged(true)
                            }
                        }
                    }
                )
            }
            .pointerInput(file.id) {
                if (isImage) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        if (newScale > 1.05f) {
                            offset += pan
                            onZoomChanged(true)
                        } else {
                            offset = Offset.Zero
                            onZoomChanged(false)
                        }
                    }
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
                    text = formatSize(file.size),
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
                        Text("Play Media")
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
                        Text(if (isLoading) "Downloading... ${((transfer?.progress ?: 0f) * 100).toInt()}%" else "Download File")
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

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

@Composable
private fun getFileIconAndColor(file: DriveItem.File): Pair<ImageVector, Color> {
    return when {
        file.mimeType.startsWith("image/") -> Icons.Default.Image to Color(0xFF4CAF50)
        file.mimeType.startsWith("video/") -> Icons.Default.VideoFile to Color(0xFFE91E63)
        file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFFFF9800)
        file.mimeType == "application/pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
        file.mimeType.contains("zip") || file.mimeType.contains("rar") || file.mimeType.contains("7z") -> Icons.Default.FolderZip to Color(0xFF9C27B0)
        file.mimeType.contains("text/") -> Icons.AutoMirrored.Filled.Article to Color(0xFF607D8B)
        file.mimeType.contains("android.package-archive") -> Icons.Default.Android to Color(0xFF3DDC84)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color.LightGray
    }
}
