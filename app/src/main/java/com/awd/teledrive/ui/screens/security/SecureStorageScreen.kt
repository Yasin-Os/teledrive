package com.awd.teledrive.ui.screens.security

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.home.DriveListItem
import com.awd.teledrive.ui.screens.home.FilterSortRow
import com.awd.teledrive.ui.screens.home.FilterType
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureStorageScreen(
    onBack: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onNavigateToTransfers: () -> Unit,
    viewModel: SecureStorageViewModel = hiltViewModel(),
    securityViewModel: SecurityViewModel = hiltViewModel()
) {
    val items by viewModel.secureItems.collectAsState()
    val availableCloudFiles by viewModel.availableCloudFiles.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val isSafePinLockEnabled by securityViewModel.isSafeFolderLockEnabled.collectAsState()

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCloudPickerSheet by remember { mutableStateOf(false) }
    var showSafeLockSettingsDialog by remember { mutableStateOf(false) }
    var showChangeSafePinDialog by remember { mutableStateOf(false) }

    var selectedSafeItems by remember { mutableStateOf(setOf<Long>()) }
    val isSelectionMode = selectedSafeItems.isNotEmpty()

    var itemToRename by remember { mutableStateOf<DriveItem?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<List<DriveItem>?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }

    if (showSafeLockSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSafeLockSettingsDialog = false },
            title = { Text("Safe Folder Lock Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Require PIN Lock", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                            Text("Ask for PIN when opening Safe folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isSafePinLockEnabled,
                            onCheckedChange = { securityViewModel.setSafeFolderLockEnabled(it) }
                        )
                    }
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = {
                            showSafeLockSettingsDialog = false
                            showChangeSafePinDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Safe Folder PIN")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafeLockSettingsDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (showChangeSafePinDialog) {
        var oldPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangeSafePinDialog = false },
            title = { Text("Change Safe Folder PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = oldPin,
                        onValueChange = { oldPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("Current PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("New PIN (4-6 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("Confirm New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = err != null,
                        supportingText = err?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length < 4) {
                        err = "Enter at least 4 digits"
                    } else if (newPin != confirmPin) {
                        err = "New PIN did not match"
                    } else if (securityViewModel.changeSafeFolderPin(oldPin, newPin)) {
                        showChangeSafePinDialog = false
                        Toast.makeText(context, "Safe Folder PIN updated", Toast.LENGTH_SHORT).show()
                    } else {
                        err = "Current PIN is incorrect"
                    }
                }) { Text("Update PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showChangeSafePinDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (itemToRename != null) {
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.new_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemToRename?.let { viewModel.renameItem(it, renameValue) }
                    itemToRename = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { viewModel.deleteItems(it) }
                    showDeleteConfirm = null
                    selectedSafeItems = emptySet()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showCloudPickerSheet) {
        var pickerFilter by remember { mutableStateOf(FilterType.ALL) }
        var pickerSearch by remember { mutableStateOf("") }
        var pickedIds by remember { mutableStateOf(setOf<Long>()) }

        val filteredCloudFiles = remember(availableCloudFiles, pickerFilter, pickerSearch) {
            val bySearch = if (pickerSearch.isBlank()) availableCloudFiles
            else availableCloudFiles.filter { it.name.contains(pickerSearch, ignoreCase = true) }

            when (pickerFilter) {
                FilterType.ALL -> bySearch
                FilterType.VIDEOS -> bySearch.filter { it.mimeType.startsWith("video/") }
                FilterType.PHOTOS -> bySearch.filter { it.mimeType.startsWith("image/") }
                FilterType.AUDIO -> bySearch.filter { it.mimeType.startsWith("audio/") }
                FilterType.DOCUMENTS -> bySearch.filter {
                    !it.mimeType.startsWith("video/") &&
                    !it.mimeType.startsWith("image/") &&
                    !it.mimeType.startsWith("audio/")
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showCloudPickerSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Cloud Files to Hide",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    if (pickedIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.hideCloudFilesInSafeFolder(pickedIds.toList())
                                showCloudPickerSheet = false
                                Toast.makeText(context, "${pickedIds.size} files hidden in Safe folder", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hide (${pickedIds.size})")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pickerSearch,
                    onValueChange = { pickerSearch = it },
                    placeholder = { Text("Search cloud files...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tabs = listOf(
                        FilterType.ALL to "All",
                        FilterType.VIDEOS to "Videos",
                        FilterType.PHOTOS to "Photos",
                        FilterType.AUDIO to "Audio",
                        FilterType.DOCUMENTS to "Documents"
                    )
                    items(tabs) { (type, label) ->
                        val selected = pickerFilter == type
                        FilterChip(
                            selected = selected,
                            onClick = { pickerFilter = type },
                            label = { Text(label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredCloudFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No cloud files found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(filteredCloudFiles, key = { it.id }) { file ->
                            val isChecked = pickedIds.contains(file.id)
                            val thumb = file.localPath ?: file.thumbnailPath

                            ListItem(
                                headlineContent = {
                                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(formatFileSize(file.size), style = MaterialTheme.typography.labelSmall)
                                },
                                leadingContent = {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (thumb != null) {
                                                AsyncImage(
                                                    model = thumb,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = when {
                                                        file.mimeType.startsWith("video/") -> Icons.Default.VideoFile
                                                        file.mimeType.startsWith("image/") -> Icons.Default.Image
                                                        file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
                                                        else -> Icons.Default.Description
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = {
                                            pickedIds = if (isChecked) pickedIds - file.id else pickedIds + file.id
                                        }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    pickedIds = if (isChecked) pickedIds - file.id else pickedIds + file.id
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    BackHandler {
        when {
            isSelectionMode -> selectedSafeItems = emptySet()
            isSearchActive -> {
                isSearchActive = false
                viewModel.onSearchQueryChange("")
            }
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    // লং-প্রেস করলে উপরে Unhide এবং Select All অপশন আসবে
                    TopAppBar(
                        title = { Text("${selectedSafeItems.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = { selectedSafeItems = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    selectedSafeItems = if (selectedSafeItems.size >= items.size) {
                                        emptySet()
                                    } else {
                                        items.map { it.id }.toSet()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (selectedSafeItems.size >= items.size && items.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = "Select All"
                                )
                            }

                            FilledTonalButton(
                                onClick = {
                                    viewModel.unhideFilesToCloud(selectedSafeItems.toList())
                                    Toast.makeText(context, "${selectedSafeItems.size} items unhidden", Toast.LENGTH_SHORT).show()
                                    selectedSafeItems = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Unhide")
                            }

                            IconButton(
                                onClick = {
                                    showDeleteConfirm = items.filter { it.id in selectedSafeItems }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier.statusBarsPadding()
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (!isSearchActive) {
                                Text("Safe folder")
                            } else {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.onSearchQueryChange(it) },
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (searchQuery.isEmpty()) {
                                        Text("Search in Safe folder...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (isSearchActive) {
                                    isSearchActive = false
                                    viewModel.onSearchQueryChange("")
                                } else {
                                    onBack()
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        },
                        actions = {
                            if (!isSearchActive) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                                IconButton(onClick = { showSafeLockSettingsDialog = true }) {
                                    Icon(Icons.Default.VpnKey, contentDescription = "Safe Folder PIN Settings")
                                }
                                IconButton(onClick = { viewModel.fetchItems() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            } else if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Close, null)
                                }
                            }
                        },
                        modifier = Modifier.statusBarsPadding()
                    )
                }
                if (!isSearchActive) {
                    FilterSortRow(
                        filterType = filterType,
                        sortOrder = sortOrder,
                        onSetFilterType = { viewModel.setFilterType(it) },
                        onSetSortOrder = { viewModel.setSortOrder(it) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showCloudPickerSheet = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add from Cloud") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.fetchItems() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Safe folder is empty", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tap 'Add from Cloud' to hide cloud videos, photos or audio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                // ডান পাশের তালা রিমুভ করা হয়েছে, লং-প্রেস করলে সিলেক্ট হয়ে উপরে Unhide বাটন আসবে
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { item ->
                        val isSelected = selectedSafeItems.contains(item.id)
                        DriveListItem(
                            item = item,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedSafeItems = if (isSelected) selectedSafeItems - item.id else selectedSafeItems + item.id
                                } else {
                                    onNavigateToPreview(item)
                                }
                            },
                            onLongClick = {
                                selectedSafeItems = if (isSelected) selectedSafeItems - item.id else selectedSafeItems + item.id
                            },
                            onStarClick = {},
                            onShareClick = {},
                            onDownloadClick = {
                                viewModel.downloadFile(item.id, item.parentChatId, item.name)
                            },
                            onRenameClick = {
                                itemToRename = item
                                renameValue = item.name
                            },
                            onDeleteClick = { showDeleteConfirm = listOf(item) }
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
