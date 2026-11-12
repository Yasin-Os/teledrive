package com.awd.teledrive.ui.screens.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.awd.teledrive.R
import com.awd.teledrive.core.FileSharingHelper
import com.awd.teledrive.ui.theme.TeledriveTheme
import kotlinx.coroutines.withContext
import java.io.File

// How many rendered pages the LRU cache keeps (visible pages + lookahead).
private const val PDF_PAGE_CACHE_SIZE = 4

/**
 * Small LRU cache of rendered PDF pages. Bitmaps are recycled when evicted
 * and when the viewer is closed. Pages currently composed on screen are
 * never recycled while they are displayed (see [markInUse]).
 */
private class PdfPageCache(private val maxSize: Int = PDF_PAGE_CACHE_SIZE) {
    private val cache = LinkedHashMap<Int, Bitmap>(maxSize, 0.75f, true)
    private val inUse = mutableSetOf<Int>()

    @Synchronized
    fun get(index: Int): Bitmap? = cache[index]?.takeUnless { it.isRecycled }

    @Synchronized
    fun put(index: Int, bitmap: Bitmap) {
        cache[index] = bitmap
        while (cache.size > maxSize) {
            val eldest = cache.entries.firstOrNull { it.key !in inUse } ?: break
            cache.remove(eldest.key)
            if (!eldest.value.isRecycled) eldest.value.recycle()
        }
    }

    @Synchronized
    fun markInUse(index: Int) {
        inUse.add(index)
    }

    @Synchronized
    fun markUnused(index: Int) {
        inUse.remove(index)
    }

    @Synchronized
    fun clear() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        inUse.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    var pageCount by remember { mutableStateOf(0) }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pdfPfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    LaunchedEffect(filePath) {
        if (!isPreview) {
            val file = File(filePath)
            if (file.exists()) {
                try {
                    // Close any previous document first (e.g. the file changed).
                    pdfRenderer?.close()
                    pdfPfd?.close()
                    pdfRenderer = null
                    pdfPfd = null
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    pdfPfd = pfd
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Ensure resources are closed when leaving. PdfRenderer.close() does NOT
    // close the ParcelFileDescriptor it was created from, so close both —
    // the descriptor leaked before.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            try { pdfRenderer?.close() } catch (e: Exception) { }
            try { pdfPfd?.close() } catch (e: Exception) { }
        }
    }

    PdfViewerContent(
        pdfRenderer = pdfRenderer,
        pageCount = pageCount,
        filePath = filePath,
        onBack = onBack,
        isLoading = pdfRenderer == null && !isPreview
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerContent(
    pdfRenderer: PdfRenderer?,
    pageCount: Int,
    filePath: String,
    onBack: () -> Unit,
    isLoading: Boolean
) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // LRU cache of rendered pages (a few pages around the visible one); its
    // bitmaps are recycled on eviction and when this viewer is closed.
    val pageCache = remember(filePath) { PdfPageCache() }
    androidx.compose.runtime.DisposableEffect(filePath) {
        onDispose { pageCache.clear() }
    }

    val fileName = filePath.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_with_other)) },
                            onClick = {
                                FileSharingHelper.openFileExternally(context, filePath, "application/pdf")
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                        )
                    }
                }
            )
        },
        containerColor = Color.DarkGray
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (pdfRenderer == null && !isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.err_load_pdf), color = Color.White)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(pageCount, key = { it }, contentType = { "pdf_page" }) { index ->
                        PdfPageItem(pdfRenderer, index, pageCache)
                    }
                }
            }
        }
    }
}

@Composable
fun PdfPageItem(renderer: PdfRenderer?, index: Int, pageCache: PdfPageCache) {
    var bitmap by remember(renderer, index) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    // This page is composed (visible or in the lazy layout's window): the
    // cache must not recycle its bitmap while it is on screen.
    androidx.compose.runtime.DisposableEffect(index) {
        pageCache.markInUse(index)
        onDispose { pageCache.markUnused(index) }
    }

    LaunchedEffect(renderer, index) {
        if (renderer != null) {
            pageCache.get(index)?.let {
                bitmap = it
                return@LaunchedEffect
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    synchronized(renderer) {
                        val page = renderer.openPage(index)

                        // Optimized memory: Limit resolution to device screen width
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val scaleFactor = screenWidth.toFloat() / page.width.toFloat()

                        val width = (page.width * scaleFactor).toInt().coerceAtMost(2000)
                        val height = (page.height * scaleFactor).toInt().coerceAtMost(3000)

                        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        newBitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        pageCache.put(index, newBitmap)
                        bitmap = newBitmap
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewer", "Error rendering page $index", e)
                }
            }
        }
    }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        val shownBitmap = bitmap
        if (shownBitmap != null && !shownBitmap.isRecycled) {
            Image(
                bitmap = shownBitmap.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp), // Placeholder height
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PdfViewerPreview() {
    TeledriveTheme {
        PdfViewerContent(pdfRenderer = null, pageCount = 0, filePath = "Sample.pdf", onBack = {}, isLoading = false)
    }
}
