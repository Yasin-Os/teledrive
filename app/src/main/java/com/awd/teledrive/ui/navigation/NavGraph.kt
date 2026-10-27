package com.awd.teledrive.ui.screens.media

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem

data class MediaAlbum(
    val id: String,
    val title: String,
    val items: List<DriveItem.File>,
    val isVideoPlaceholder: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: MediaViewModel = hiltViewModel()
) {
    val mediaItems by viewModel.mediaItems.collectAsState()
    val folders by viewModel.folders.collectAsState()

    MediaContent(
        mediaItems = mediaItems,
        folders = folders,
        onNavigateToPreview = onNavigateToPreview,
        onCreateAlbum = viewModel::createAlbum,
        onDownload = viewModel::downloadFile,
        onDelete = viewModel::deleteItems,
        onToggleStar = viewModel::toggleStarred
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaContent(
    mediaItems: List<DriveItem.File>,
    folders: List<DriveItem.Folder>,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onCreateAlbum: (String) -> Unit = {},
    onDownload: (DriveItem.File) -> Unit,
    onDelete: (List<DriveItem.File>) -> Unit,
    onToggleStar: (DriveItem.File) -> Unit
) {
    val context = LocalContext.current
    var selectedAlbum by remember { mutableStateOf<MediaAlbum?>(null) }
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }

    val topAlbums = remember(mediaItems) {
        val allPhotos = mediaItems.filter { it.mimeType.startsWith("image/") }
        val videos = mediaItems.filter { it.mimeType.startsWith("video/") }
        val camera = mediaItems.filter {
            val n = it.name.lowercase()
            n.startsWith("img_") || n.startsWith("vid_") || n.startsWith("pxl_") || n.startsWith("dcim") || n.matches(Regex("^20\\d{6}_.*"))
        }
        val screenshots = mediaItems.filter { it.name.contains("screenshot", ignoreCase = true) }
        val screenRecordings = mediaItems.filter {
            val n = it.name.lowercase()
            it.mimeType.startsWith("video/") && (n.contains("screen") || n.contains("record") || n.startsWith("svid_") || n.startsWith("lv_"))
        }

        listOf(
            MediaAlbum("all_photos", "All Photos", allPhotos.ifEmpty { mediaItems }),
            MediaAlbum("camera", "Camera", camera),
            MediaAlbum("videos", "Videos", videos, isVideoPlaceholder = true),
            MediaAlbum("screenshots", "Screenshots", screenshots),
            MediaAlbum("screen_recordings", "Screen Recordings", screenRecordings, isVideoPlaceholder = true)
        )
    }

    val moreAlbums = remember(mediaItems, folders) {
        val list = mutableListOf<MediaAlbum>()
        val fbItems = mediaItems.filter { it.name.contains("fb_", true) || it.name.contains("facebook", true) || it.name.contains("received_", true) }
        val waItems = mediaItems.filter { it.name.contains("wa0", true) || it.name.contains("whatsapp", true) }
        val dlItems = mediaItems.filter { (it.virtualParentId ?: "0") == "0" }

        list.add(MediaAlbum("downloads", "Downloads", dlItems, isVideoPlaceholder = true))
        if (fbItems.isNotEmpty()) list.add(MediaAlbum("facebook", "Facebook", fbItems))
        if (waItems.isNotEmpty()) list.add(MediaAlbum("whatsapp", "WhatsApp Video", waItems))

        folders.forEach { folder ->
            val vId = folder.virtualId ?: ""
            val folderMedia = mediaItems.filter { it.virtualParentId == vId }
            list.add(MediaAlbum("vf_$vId", folder.name, folderMedia))
        }
        list
    }

    BackHandler(enabled = isSelectionMode || selectedAlbum != null) {
        if (isSelectionMode) {
            selectedItems = emptySet()
        } else if (selectedAlbum != null) {
            selectedAlbum = null
        }
    }

    if (showCreateAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showCreateAlbumDialog = false },
            title = { Text("New Album") },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("Album Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newAlbumName.isNotBlank()) {
                        onCreateAlbum(newAlbumName.trim())
                        newAlbumName = ""
                        showCreateAlbumDialog = false
                    }
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbumDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val itemsToDelete = mediaItems.filter { it.id in selectedItems }
                    onDelete(itemsToDelete)
                    showDeleteConfirm = false
                    selectedItems = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(onClick = { selectedItems = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedItems.forEach { id -> mediaItems.find { it.id == id }?.let { onToggleStar(it) } }
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Default.Star, contentDescription = stringResource(R.string.add_star))
                        }
                        IconButton(onClick = {
                            val count = selectedItems.size
                            selectedItems.forEach { id -> mediaItems.find { it.id == id }?.let { onDownload(it) } }
                            selectedItems = emptySet()
                            Toast.makeText(context, context.getString(R.string.starting_downloads, count), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                )
            } else if (selectedAlbum != null) {
                TopAppBar(
                    title = {
                        Column {
                            Text(selectedAlbum!!.title, style = MaterialTheme.typography.titleLarge)
                            Text("${selectedAlbum!!.items.size} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(onClick = { selectedAlbum = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showCreateAlbumDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Create Album")
                        }
                        IconButton(onClick = {
                            selectedAlbum = topAlbums.firstOrNull()
                        }) {
                            Icon(Icons.Default.CheckBox, contentDescription = "Select")
                        }
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                    }
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        if (selectedAlbum == null) {
            // মেইন Albums স্ক্রিন (২য় স্ক্রিনশটের মতো)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                items(topAlbums, key = { it.id }) { album ->
                    AlbumGridCard(
                        album = album,
                        onClick = { selectedAlbum = album }
                    )
                }

                item(span = { GridItemSpan(3) }) {
                    Text(
                        text = "More Albums",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }

                items(moreAlbums, key = { it.id }) { album ->
                    AlbumGridCard(
                        album = album,
                        onClick = { selectedAlbum = album }
                    )
                }
            }
        } else {
            val currentAlbumItems = selectedAlbum!!.items
            if (currentAlbumItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = padding.calculateBottomPadding() + 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                ) {
                    itemsIndexed(currentAlbumItems) { _, item ->
                        val isSelected = selectedItems.contains(item.id)
                        MediaGridItem(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                } else {
                                    onNavigateToPreview(item)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    selectedItems = setOf(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumGridCard(
    album: MediaAlbum,
    onClick: () -> Unit
) {
    val coverItem = album.items.firstOrNull()
    val coverModel = coverItem?.localPath ?: coverItem?.thumbnailPath

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (album.isVideoPlaceholder) Icons.Default.Videocam else Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${album.items.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: DriveItem.File,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.localPath ?: item.thumbnailPath,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (item.mimeType.startsWith("video/")) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
            
            if (item.isStarred) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
