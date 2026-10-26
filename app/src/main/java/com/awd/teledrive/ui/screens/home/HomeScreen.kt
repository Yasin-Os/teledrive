package com.awd.teledrive.ui.screens.home

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.core.ConnectivityObserver
import com.awd.teledrive.domain.model.DriveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    val totalStorageUsed by viewModel.totalStorageUsed.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val currentFolderId by viewModel.currentFolderId.collectAsState()
    val currentFolderName by viewModel.currentFolderName.collectAsState()
    val currentVirtualFolderId by viewModel.currentVirtualFolderId.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val connectivityStatus by viewModel.connectivityStatus.collectAsState()

    HomeContent(
        items = items,
        totalStorageUsed = totalStorageUsed,
        searchQuery = searchQuery,
        sortOrder = sortOrder,
        filterType = filterType,
        isRefreshing = isRefreshing,
        isInitialLoading = isInitialLoading,
        currentFolderId = currentFolderId,
        currentVirtualFolderId = currentVirtualFolderId,
        currentFolderName = currentFolderName,
        isGridView = isGridView,
        connectivityStatus = connectivityStatus,
        onNavigateToTransfers = onNavigateToTransfers,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToPreview = onNavigateToPreview,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onToggleViewMode = viewModel::toggleViewMode,
        onSetSortOrder = viewModel::setSortOrder,
        onSetFilterType = viewModel::setFilterType,
        onCreateFolder = viewModel::createFolder,
        isPasswordSet = viewModel.isPasswordSet(),
        onUploadFile = viewModel::uploadFile,
        onDeleteItems = viewModel::deleteItems,
        onMoveItems = viewModel::moveItems,
        onMoveFolderContents = viewModel::moveFolderContentsAndDelete,
        onDownloadFile = viewModel::downloadFile,
        onDownloadFolderContents = viewModel::downloadFolderContents,
        onToggleStarred = viewModel::toggleStarred,
        onNavigateToFolder = { chatId, vId, name -> viewModel.navigateToFolder(chatId, vId, name) },
        onNavigateBack = viewModel::navigateBack,
        onRefresh = viewModel::fetchItems,
        onRenameItem = viewModel::renameItem,
        onGetInviteLink = viewModel::getFolderInviteLink,
        onGetMembers = viewModel::getFolderMembers,
        onShareFile = viewModel::shareFileToPhone,
        onLogout = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    items: List<DriveItem>,
    totalStorageUsed: Long,
    searchQuery: String,
    sortOrder: SortOrder,
    filterType: FilterType,
    isRefreshing: Boolean,
    isInitialLoading: Boolean,
    currentFolderId: Long?,
    currentVirtualFolderId: String,
    currentFolderName: String?,
    isGridView: Boolean,
    connectivityStatus: ConnectivityObserver.Status,
    onNavigateToTransfers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleViewMode: () -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onSetFilterType: (FilterType) -> Unit,
    onCreateFolder: (String, Boolean, Boolean) -> Unit,
    isPasswordSet: Boolean,
    onUploadFile: (String, String) -> Unit,
    onDeleteItems: (List<DriveItem>) -> Unit,
    onMoveItems: (Set<Long>, Long) -> Unit,
    onMoveFolderContents: (Long, Long) -> Unit,
    onDownloadFile: (Long, Long, String) -> Unit,
    onDownloadFolderContents: (DriveItem.Folder) -> Unit,
    onToggleStarred: (DriveItem) -> Unit,
    onNavigateToFolder: (Long?, String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onRenameItem: (DriveItem, String) -> Unit,
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showNewSheet by remember { mutableStateOf(false) }

    val isOffline = connectivityStatus == ConnectivityObserver.Status.Unavailable || 
                    connectivityStatus == ConnectivityObserver.Status.Lost

    var selectedItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedItems.isNotEmpty()
    var showFolderDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<List<DriveItem>?>(null) }
    var itemToRename by remember { mutableStateOf<DriveItem?>(null) }
    var renameValue by remember { mutableStateOf("") }

    if (itemToRename != null) {
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemToRename?.let { onRenameItem(it, renameValue) }
                    itemToRename = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    var shareItem by remember { mutableStateOf<DriveItem?>(null) }
    var folderToDownload by remember { mutableStateOf<DriveItem.Folder?>(null) }
    var largeFileToUpload by remember { mutableStateOf<Pair<File, String>?>(null) }
    var isPreparingFile by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        
        scope.launch {
            isPreparingFile = true
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val fileName = cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        c.moveToFirst()
                        c.getString(nameIndex)
                    } ?: "file_${System.currentTimeMillis()}"

                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, fileName)
                    inputStream?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (tempFile.length() > 2000 * 1024 * 1024L) {
                            largeFileToUpload = tempFile to fileName
                        } else {
                            onUploadFile(tempFile.absolutePath, fileName)
                        }
                    }
                }
            }
            isPreparingFile = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            scope.launch {
                isPreparingFile = true
                withContext(Dispatchers.IO) {
                    val fileName = "IMG_${System.currentTimeMillis()}.jpg"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { out ->
                        it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    withContext(Dispatchers.Main) {
                        onUploadFile(file.absolutePath, fileName)
                    }
                }
                isPreparingFile = false
            }
        }
    }

    val pressBackMsg = stringResource(R.string.press_back_again)
    var backPressedOnce by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showNewSheet -> showNewSheet = false
            isSearchActive -> isSearchActive = false
            isSelectionMode -> selectedItems = emptySet()
            filterType != FilterType.ALL -> onSetFilterType(FilterType.ALL)
            (currentFolderId != null || currentVirtualFolderId != "0") -> onNavigateBack()
            else -> {
                if (backPressedOnce) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    backPressedOnce = true
                    Toast.makeText(context, pressBackMsg, Toast.LENGTH_SHORT).show()
                    scope.launch {
                        delay(2.seconds)
                        backPressedOnce = false
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { onDeleteItems(it) }
                    showDeleteConfirm = null
                    selectedItems = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (shareItem != null) {
        ShareDialog(
            item = shareItem!!,
            onDismiss = { shareItem = null },
            onGetInviteLink = onGetInviteLink,
            onGetMembers = onGetMembers,
            onShareFile = onShareFile
        )
    }

    if (folderToDownload != null) {
        AlertDialog(
            onDismissRequest = { folderToDownload = null },
            title = { Text(stringResource(R.string.folder_download_warning_title)) },
            text = { Text(stringResource(R.string.folder_download_warning_message, folderToDownload?.name ?: "")) },
            confirmButton = {
                Button(onClick = {
                    folderToDownload?.let { folder ->
                        onDownloadFolderContents(folder)
                    }
                    folderToDownload = null
                }) { Text(stringResource(R.string.download_contents)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDownload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (largeFileToUpload != null) {
        AlertDialog(
            onDismissRequest = { largeFileToUpload = null },
            title = { Text(stringResource(R.string.large_file_title)) },
            text = { Text(stringResource(R.string.large_file_message, largeFileToUpload?.second ?: "")) },
            confirmButton = {
                Button(onClick = {
                    largeFileToUpload?.let { (file, name) ->
                        onUploadFile(file.absolutePath, name)
                    }
                    largeFileToUpload = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { largeFileToUpload = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (isPreparingFile) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.preparing_file)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.copying_to_temp))
                }
            },
            confirmButton = {}
        )
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(stringResource(R.string.create_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    NewActionItem(Icons.Default.CreateNewFolder, stringResource(R.string.folder)) {
                        showNewSheet = false
                        showFolderDialog = true
                    }
                    NewActionItem(Icons.Default.FileUpload, stringResource(R.string.upload)) {
                        showNewSheet = false
                        multiFilePickerLauncher.launch("*/*")
                    }
                    NewActionItem(Icons.Default.CameraAlt, stringResource(R.string.camera)) {
                        showNewSheet = false
                        cameraLauncher.launch(null)
                    }
                }
            }
        }
    }

    // ক্লিন ফোল্ডার তৈরির ডায়ালগ (চ্যানেল তৈরির জটিল অপশন বাদ দেওয়া হয়েছে)
    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(R.string.folder_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName, true, false)
                            newFolderName = ""
                            showFolderDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = if (isSelectionMode) "selection" else if ((currentFolderId != null || currentVirtualFolderId != "0") && !isSearchActive) "folder" else "main",
                transitionSpec = {
                    (fadeIn() + slideInVertically { -it / 2 }).togetherWith(fadeOut() + slideOutVertically { -it / 2 })
                },
                label = "TopBarTransition"
            ) { state ->
                when (state) {
                    "selection" -> {
                        TopAppBar(
                            title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                            modifier = Modifier.statusBarsPadding(),
                            navigationIcon = {
                                IconButton(onClick = { selectedItems = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val files = items.filterIsInstance<DriveItem.File>()
                                        if (selectedItems.size >= files.size && files.isNotEmpty()) {
                                            selectedItems = emptySet()
                                        } else {
                                            selectedItems = files.map { it.id }.toSet()
                                        }
                                    }
                                ) {
                                    val files = items.filterIsInstance<DriveItem.File>()
                                    Icon(
                                        if (selectedItems.size >= files.size && files.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = stringResource(R.string.select_all)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        val selectedFiles = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.File>()
                                        val selectedFolders = items.filter { it.id in selectedItems }.filterIsInstance<DriveItem.Folder>()
                                        
                                        selectedFiles.forEach { onDownloadFile(it.id, it.parentChatId, it.name) }
                                        selectedFolders.forEach { onDownloadFolderContents(it) }
                                        
                                        selectedItems = emptySet()
                                        Toast.makeText(context, context.getString(R.string.starting_downloads, selectedFiles.size + selectedFolders.size), Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download))
                                }
                                IconButton(
                                    onClick = {
                                        if (isOffline) {
                                            Toast.makeText(context, context.getString(R.string.offline_msg), Toast.LENGTH_SHORT).show()
                                            return@IconButton
                                        }
                                        val itemsToDelete = items.filter { it.id in selectedItems }
                                        showDeleteConfirm = itemsToDelete
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        )
                    }
                    "folder" -> {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = currentFolderName ?: stringResource(R.string.my_drive),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { onNavigateBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { isSearchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_teledrive))
                                    }
                                    IconButton(onClick = { 
                                        onRefresh()
                                        Toast.makeText(context, context.getString(R.string.refreshing), Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    }
                                    IconButton(onClick = { onToggleViewMode() }) {
                                        Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.statusBarsPadding()
                            )
                            FilterSortRow(
                                filterType = filterType,
                                sortOrder = sortOrder,
                                onSetFilterType = onSetFilterType,
                                onSetSortOrder = onSetSortOrder,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }
                    "main" -> {
                        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable { isSearchActive = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    if (!isSearchActive) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Search in MyCloud...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = onNavigateToTransfers) {
                                            Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.transfers))
                                        }
                                        IconButton(onClick = { 
                                            onRefresh()
                                            Toast.makeText(context, context.getString(R.string.refreshing), Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                        }
                                        IconButton(onClick = { onToggleViewMode() }) {
                                            Icon(if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, contentDescription = null)
                                        }
                                    } else {
                                        IconButton(onClick = { isSearchActive = false }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = onSearchQueryChange,
                                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (searchQuery.isEmpty()) {
                                                Text("Search files...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { onSearchQueryChange("") }) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (!isSearchActive) {
                                Spacer(modifier = Modifier.height(12.dp))

                                FilterSortRow(
                                    filterType = filterType,
                                    sortOrder = sortOrder,
                                    onSetFilterType = onSetFilterType,
                                    onSetSortOrder = onSetSortOrder
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isOffline) {
                ExtendedFloatingActionButton(
                    onClick = { showNewSheet = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.new_label)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isInitialLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    if (isRefreshing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                
                    if (isOffline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.offline_msg), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    val showDashboard = currentVirtualFolderId == "0" && searchQuery.isEmpty() && filterType == FilterType.ALL

                    if (items.isEmpty() && !showDashboard) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (searchQuery.isEmpty()) Icons.Default.CloudQueue else Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(80.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchQuery.isEmpty()) stringResource(R.string.drive_empty) else stringResource(R.string.no_results),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.tap_to_add),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (showDashboard) {
                                    item(span = { GridItemSpan(2) }) {
                                        GoogleFilesCategoriesSection(
                                            totalStorageUsed = totalStorageUsed,
                                            allItems = items,
                                            onSelectCategory = onSetFilterType
                                        )
                                    }
                                }
                                items(items) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    DriveGridItem(
                                        item = item,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                            } else {
                                                if (item is DriveItem.File) onNavigateToPreview(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(null, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) {
                                                onDownloadFile(item.id, item.parentChatId, item.name)
                                            } else if (item is DriveItem.Folder) {
                                                folderToDownload = item
                                            }
                                        },
                                        onMoveClick = null,
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = {
                                            showDeleteConfirm = listOf(item)
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = 88.dp, top = 4.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (showDashboard) {
                                    item {
                                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                            GoogleFilesCategoriesSection(
                                                totalStorageUsed = totalStorageUsed,
                                                allItems = items,
                                                onSelectCategory = onSetFilterType
                                            )
                                        }
                                    }
                                }
                                items(items) { item ->
                                    val isSelected = selectedItems.contains(item.id)
                                    DriveListItem(
                                        item = item,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedItems = if (isSelected) selectedItems - item.id else selectedItems + item.id
                                            } else {
                                                if (item is DriveItem.File) onNavigateToPreview(item)
                                                else if (item is DriveItem.Folder) onNavigateToFolder(null, item.virtualId, item.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedItems = setOf(item.id)
                                            }
                                        },
                                        onStarClick = { onToggleStarred(item) },
                                        onShareClick = { shareItem = item },
                                        onDownloadClick = {
                                            if (item is DriveItem.File) {
                                                onDownloadFile(item.id, item.parentChatId, item.name)
                                            } else if (item is DriveItem.Folder) {
                                                folderToDownload = item
                                            }
                                        },
                                        onMoveClick = null,
                                        onRenameClick = {
                                            itemToRename = item
                                            renameValue = item.name
                                        },
                                        onDeleteClick = {
                                            showDeleteConfirm = listOf(item)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Google Files স্টাইলের স্টোরেজ ও ক্যাটাগরি ড্যাশবোর্ড
@Composable
fun GoogleFilesCategoriesSection(
    totalStorageUsed: Long,
    allItems: List<DriveItem>,
    onSelectCategory: (FilterType) -> Unit
) {
    val files = allItems.filterIsInstance<DriveItem.File>()
    val videoCount = files.count { it.mimeType.startsWith("video/") }
    val photoCount = files.count { it.mimeType.startsWith("image/") }
    val audioCount = files.count { it.mimeType.startsWith("audio/") }
    val appCount = files.count { it.mimeType.contains("android.package-archive") || it.name.lowercase().endsWith(".apk") }
    val archiveCount = files.count { 
        val n = it.name.lowercase()
        it.mimeType.contains("zip") || it.mimeType.contains("rar") || it.mimeType.contains("7z") || n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
    }
    val docCount = (files.size - (videoCount + photoCount + audioCount + appCount + archiveCount)).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth()) {
        // ক্লাউড স্টোরেজ ব্যানার কার্ড
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Telegram Cloud Storage", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("${formatSize(totalStorageUsed)} used • Unlimited Free Space", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )

        // Google Files স্টাইলের ২ কলামের ক্যাটাগরি গ্রিড
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CategoryCard(
                    title = "Apps (APK)",
                    count = "$appCount files",
                    icon = Icons.Default.Android,
                    color = Color(0xFF3DDC84),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.APPS) }
                )
                CategoryCard(
                    title = "ZIP & RAR",
                    count = "$archiveCount files",
                    icon = Icons.Default.FolderZip,
                    color = Color(0xFF9C27B0),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.ARCHIVES) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CategoryCard(
                    title = "Videos",
                    count = "$videoCount files",
                    icon = Icons.Default.VideoFile,
                    color = Color(0xFFE91E63),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.VIDEOS) }
                )
                CategoryCard(
                    title = "Audio",
                    count = "$audioCount files",
                    icon = Icons.Default.AudioFile,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.AUDIO) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CategoryCard(
                    title = "Images",
                    count = "$photoCount files",
                    icon = Icons.Default.Image,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.PHOTOS) }
                )
                CategoryCard(
                    title = "Documents",
                    count = "$docCount files",
                    icon = Icons.Default.Description,
                    color = Color(0xFF2196F3),
                    modifier = Modifier.weight(1f),
                    onClick = { onSelectCategory(FilterType.DOCUMENTS) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "All Folders & Files",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
    }
}

@Composable
fun CategoryCard(
    title: String,
    count: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NewActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun FilterSortRow(
    filterType: FilterType,
    sortOrder: SortOrder,
    onSetFilterType: (FilterType) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            val categories = FilterType.entries
            items(categories) { category ->
                FilterChip(
                    selected = filterType == category,
                    onClick = { onSetFilterType(category) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    label = {
                        Text(
                            when (category) {
                                FilterType.ALL -> stringResource(R.string.filter_all)
                                FilterType.APPS -> "Apps (APK)"
                                FilterType.ARCHIVES -> "ZIP / RAR"
                                FilterType.VIDEOS -> stringResource(R.string.filter_videos)
                                FilterType.AUDIO -> stringResource(R.string.filter_audio)
                                FilterType.PHOTOS -> stringResource(R.string.filter_photos)
                                FilterType.DOCUMENTS -> stringResource(R.string.filter_documents)
                            }
                        )
                    },
                    leadingIcon = if (filterType == category) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    } else null
                )
            }
        }

        var showSortMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { showSortMenu = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_name)) },
                    onClick = { onSetSortOrder(SortOrder.NAME); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.NAME) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_date)) },
                    onClick = { onSetSortOrder(SortOrder.DATE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.DATE) Icon(Icons.Default.Check, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_size)) },
                    onClick = { onSetSortOrder(SortOrder.SIZE); showSortMenu = false },
                    leadingIcon = { if (sortOrder == SortOrder.SIZE) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveListItem(
    item: DriveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)

    ListItem(
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isSecure) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        },
        supportingContent = {
            if (item is DriveItem.File) {
                Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.folder), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            Surface(
                color = if (item is DriveItem.Folder) MaterialTheme.colorScheme.primaryContainer else color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.localPath ?: item.thumbnailPath
                    } else null

                    if (thumbnailModel != null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(24.dp)
                            )
                            AsyncImage(
                                model = thumbnailModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                var showItemMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showItemMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                
                var showInfoDialog by remember { mutableStateOf(false) }
                val isVirtual = item is DriveItem.Folder && item.isVirtual

                DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                    if (!isVirtual && !isSecure) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = { 
                                onShareClick()
                                showItemMenu = false 
                            },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                    }

                    if (!isSecure) {
                        DropdownMenuItem(
                            text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                            onClick = { 
                                onStarClick()
                                showItemMenu = false 
                            },
                            leadingIcon = { 
                                Icon(
                                    imageVector = if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                                    contentDescription = null,
                                    tint = if (item.isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                ) 
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.info)) },
                        onClick = { showInfoDialog = true; showItemMenu = false },
                        leadingIcon = { Icon(Icons.Default.Info, null) }
                    )

                    onRenameClick?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = {
                                it()
                                showItemMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                    }

                    if (!isSecure) {
                        if (item is DriveItem.File) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download)) },
                                onClick = { 
                                    onDownloadClick()
                                    showItemMenu = false 
                                },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                        } else if (item is DriveItem.Folder) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_contents)) },
                                onClick = { 
                                    onDownloadClick()
                                    showItemMenu = false 
                                },
                                leadingIcon = { Icon(Icons.Default.Download, null) }
                            )
                        }
                    }

                    onDeleteClick?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                it()
                                showItemMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
                
                if (showInfoDialog) {
                    InfoDialog(item = item, onDismiss = { showInfoDialog = false })
                }
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DriveGridItem(
    item: DriveItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onRenameClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    var showItemMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    val (icon, color) = getFileIconAndColor(item)
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                        .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                ) {
                    val thumbnailModel = if (item is DriveItem.File) {
                        item.localPath ?: item.thumbnailPath
                    } else null

                    if (thumbnailModel != null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = color
                            )
                            AsyncImage(
                                model = thumbnailModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().background(Color.Transparent),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = color
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSecure) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp).padding(horizontal = 2.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                    Box {
                        IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        val isVirtual = item is DriveItem.Folder && item.isVirtual

                        DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                            if (!isVirtual && !isSecure) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share)) },
                                    onClick = { onShareClick(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Share, null) }
                                )
                            }
                            if (!isSecure) {
                                DropdownMenuItem(
                                    text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                                    onClick = { onStarClick(); showItemMenu = false },
                                    leadingIcon = { 
                                        Icon(
                                            imageVector = if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline,
                                            contentDescription = null,
                                            tint = if (item.isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                        ) 
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.info)) },
                                onClick = { showInfoDialog = true; showItemMenu = false },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            onRenameClick?.let {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                            }
                            if (!isSecure) {
                                if (item is DriveItem.File) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download)) },
                                        onClick = { onDownloadClick(); showItemMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Download, null) }
                                    )
                                } else if (item is DriveItem.Folder) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.download_contents)) },
                                        onClick = { onDownloadClick(); showItemMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Download, null) }
                                    )
                                }
                            }
                            onDeleteClick?.let {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = { it(); showItemMenu = false },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                }
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    }
    
    if (showInfoDialog) {
        InfoDialog(item = item, onDismiss = { showInfoDialog = false })
    }
}

@Composable
fun InfoDialog(item: DriveItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.item_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.name_label, item.name), style = MaterialTheme.typography.bodyMedium)
                if (item is DriveItem.File) {
                    Text(stringResource(R.string.size_label, formatSize(item.size)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.type_label, item.mimeType), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.status_label, 
                            if (item.localPath != null) stringResource(R.string.available_offline) else stringResource(R.string.cloud_only)
                        ), 
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(stringResource(R.string.type_label, stringResource(R.string.folder)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ShareDialog(
    item: DriveItem,
    onDismiss: () -> Unit,
    onGetInviteLink: (Long, (String?) -> Unit) -> Unit,
    onGetMembers: (Long, (List<TdApi.User>) -> Unit) -> Unit,
    onShareFile: (String, Long, (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var shareToPhone by remember { mutableStateOf("") }
    var isSharingFile by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item is DriveItem.Folder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.share_item, item.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (item is DriveItem.File) {
                    Text(stringResource(R.string.share_to_telegram), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = shareToPhone,
                        onValueChange = { shareToPhone = it },
                        placeholder = { Text("+880...") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )
                    
                    if (isSharingFile) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (item is DriveItem.File) {
                Button(
                    onClick = {
                        if (shareToPhone.isNotBlank()) {
                            isSharingFile = true
                            onShareFile(shareToPhone, item.id) { success, error ->
                                isSharingFile = false
                                if (success) {
                                    Toast.makeText(context, context.getString(R.string.share_success), Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.failed_with_error, error ?: ""), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isSharingFile && shareToPhone.isNotBlank()
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun getFileIconAndColor(item: DriveItem): Pair<ImageVector, Color> {
    if (item is DriveItem.Folder) {
        return when {
            item.isSecure -> Icons.Default.Folder to Color(0xFF2196F3)
            item.isVirtual -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
            else -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
        }
    } else {
        val file = item as DriveItem.File
        val lowerName = file.name.lowercase()
        return when {
            file.mimeType.contains("android.package-archive") || lowerName.endsWith(".apk") -> Icons.Default.Android to Color(0xFF3DDC84)
            file.mimeType.contains("zip") || file.mimeType.contains("rar") || file.mimeType.contains("7z") || lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> Icons.Default.FolderZip to Color(0xFF9C27B0)
            file.mimeType.startsWith("image/") -> Icons.Default.Image to Color(0xFF4CAF50)
            file.mimeType.startsWith("video/") -> Icons.Default.VideoFile to Color(0xFFE91E63)
            file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile to Color(0xFFFF9800)
            file.mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
            file.mimeType.contains("text/") || lowerName.endsWith(".txt") -> Icons.AutoMirrored.Filled.Article to Color(0xFF607D8B)
            else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
