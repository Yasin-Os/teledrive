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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// How many rendered pages the LRU cache keeps (visible pages + lookahead).
private const val PDF_PAGE_CACHE_SIZE = 4

/**
 * H17: owns a [PdfRenderer] and its backing [ParcelFileDescriptor] behind a
 * shared lock so closing on dispose can never race an in-flight render on
 * Dispatchers.IO ("Current page not closed" / native use-after-close).
 */
class PdfDocumentHolder(
    val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor
) {
    val lock = Any()

    @Volatile
    var isClosed: Boolean = false
        private set

    fun closeAsync() {
        if (isClosed) return
        isClosed = true
        CoroutineScope(Dispatchers.IO).launch {
            closeLocked()
        }
    }

    fun closeLocked() {
        isClosed = true
        synchronized(lock) {
            try { renderer.close() } catch (_: Exception) { }
            try { pfd.close() } catch (_: Exception) { }
        }
    }
}

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
    val isPreview = LocalInspectionMode.current

    var pageCount by remember { mutableStateOf(0) }
    var documentHolder by remember { mutableStateOf<PdfDocumentHolder?>(null) }
    var isLoading by remember(filePath) { mutableStateOf(!isPreview) }

    LaunchedEffect(filePath) {
        if (!isPreview) {
            isLoading = true
            // Close any previous document safely off the main thread.
            val previous = documentHolder
            documentHolder = null
            pageCount = 0
            previous?.closeAsync()

            var opened: PdfDocumentHolder? = null
            try {
                // H17: open the PDF descriptor and renderer on Dispatchers.IO.
                opened = withContext(Dispatchers.IO) {
                    val file = File(filePath)
                    if (!file.exists()) return@withContext null
                    var pfd: ParcelFileDescriptor? = null
                    try {
                        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        PdfDocumentHolder(renderer, pfd)
                    } catch (e: Exception) {
                        Log.e("PdfViewer", "Error opening PDF", e)
                        try { pfd?.close() } catch (_: Exception) { }
                        null
                    }
                }
                if (opened != null) {
                    val count = synchronized(opened.lock) {
                        if (opened.isClosed) 0 else opened.renderer.pageCount
                    }
                    documentHolder = opened
                    pageCount = count
                    opened = null
                }
            } catch (e: CancellationException) {
                opened?.closeAsync()
                throw e
            } finally {
                isLoading = false
            }
        }
    }

    // Ensure resources are closed safely under the renderer lock when leaving.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val current = documentHolder
            documentHolder = null
            current?.closeAsync()
        }
    }

    PdfViewerContent(
        documentHolder = documentHolder,
        pageCount = pageCount,
        filePath = filePath,
        onBack = onBack,
        isLoading = isLoading
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerContent(
    documentHolder: PdfDocumentHolder?,
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
                    if (!FileSharingHelper.isPrivateVaultTempPath(context, filePath)) {
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
                }
            )
        },
        containerColor = Color.DarkGray
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (documentHolder == null || pageCount <= 0) {
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
                        PdfPageItem(documentHolder, index, pageCache)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(holder: PdfDocumentHolder?, index: Int, pageCache: PdfPageCache) {
    var bitmap by remember(holder, index) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    // This page is composed (visible or in the lazy layout's window): the
    // cache must not recycle its bitmap while it is on screen.
    androidx.compose.runtime.DisposableEffect(index) {
        pageCache.markInUse(index)
        onDispose { pageCache.markUnused(index) }
    }

    LaunchedEffect(holder, index) {
        if (holder != null) {
            pageCache.get(index)?.let {
                bitmap = it
                return@LaunchedEffect
            }
            val rendered = withContext(Dispatchers.IO) {
                try {
                    synchronized(holder.lock) {
                        if (holder.isClosed) return@synchronized null
                        val page = holder.renderer.openPage(index)
                        try {
                            // H17: guard against malformed PDFs with 0/negative page dimensions.
                            if (page.width <= 0 || page.height <= 0) return@synchronized null

                            // Optimized memory: Limit resolution to device screen width
                            val displayMetrics = context.resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels.coerceAtLeast(1)
                            val scaleFactor = screenWidth.toFloat() / page.width.toFloat()

                            // L19: capping width and height independently
                            // stretched/squashed pages that hit both caps.
                            // One uniform scale keeps the aspect ratio.
                            val fitWidth = (page.width * scaleFactor).toInt().coerceAtLeast(1)
                            val fitHeight = (page.height * scaleFactor).toInt().coerceAtLeast(1)
                            val extraScale = minOf(2000f / fitWidth, 3000f / fitHeight, 1f)
                            val width = (fitWidth * extraScale).toInt()
                            val height = (fitHeight * extraScale).toInt()
                            if (width <= 0 || height <= 0) return@synchronized null

                            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            newBitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(newBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pageCache.put(index, newBitmap)
                            newBitmap
                        } finally {
                            page.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("PdfViewer", "Error rendering page $index", e)
                    null
                }
            }
            if (rendered != null && !rendered.isRecycled) {
                bitmap = rendered
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
        PdfViewerContent(documentHolder = null, pageCount = 0, filePath = "Sample.pdf", onBack = {}, isLoading = false)
    }
}
