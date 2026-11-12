package com.awd.teledrive.ui.screens.insights

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.data.local.DriveDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderScreen(
    onBack: () -> Unit,
    viewModel: DuplicateFinderViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsState()
    val totalWasted by viewModel.totalWasted.collectAsState()
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    val groupItems by viewModel.groupItems.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val lastScanHashed by viewModel.lastScanHashed.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()

    val context = LocalContext.current
    var groupToDelete by remember { mutableStateOf<DriveDao.DuplicateGroup?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.duplicate_finder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (groups.isEmpty()) stringResource(R.string.no_duplicates)
                        else stringResource(R.string.duplicates_wasted, FileUtils.formatSize(totalWasted)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.duplicates_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.scan() },
                            enabled = !isScanning && !isDeleting
                        ) {
                            Text(stringResource(R.string.scan_for_duplicates))
                        }
                        if (groups.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { showDeleteAllConfirm = true },
                                enabled = !isScanning && !isDeleting
                            ) {
                                Text(stringResource(R.string.delete_all_duplicates))
                            }
                        }
                    }
                    if (isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = scanProgress?.let { (done, total) ->
                                stringResource(R.string.scanning_files, done, total)
                            } ?: stringResource(R.string.scanning_files, 0, 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    lastScanHashed?.let { hashed ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.scan_finished, hashed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_duplicates_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                groups.forEach { group ->
                    val expanded = expandedGroups.contains(group.contentHash)
                    val items = groupItems[group.contentHash].orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Column {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = "${group.count} copies • ${FileUtils.formatSize(group.size)} each",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "Wasted: ${FileUtils.formatSize(group.wastedBytes)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.toggleGroup(group) }) {
                                        Icon(
                                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null
                                        )
                                    }
                                }
                            )
                            if (expanded) {
                                HorizontalDivider()
                                if (items.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.status_waiting),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                } else {
                                    items.forEachIndexed { index, entity ->
                                        ListItem(
                                            headlineContent = {
                                                Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            },
                                            supportingContent = {
                                                Text(
                                                    text = formatDate(entity.createdAt),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            },
                                            trailingContent = {
                                                if (index == 0) {
                                                    Text(
                                                        text = "Keep",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { groupToDelete = group },
                                            enabled = !isDeleting
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.keep_newest_delete_rest))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text(stringResource(R.string.delete_all_duplicates)) },
            text = {
                Text(stringResource(R.string.delete_duplicates_confirm, group.count - 1))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = group
                    groupToDelete = null
                    viewModel.deleteGroupCopies(target) { deleted ->
                        Toast.makeText(
                            context,
                            "Deleted $deleted duplicate copies",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteAllConfirm) {
        val totalCopies = groups.sumOf { it.count - 1 }
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.delete_all_duplicates)) },
            text = {
                Text(stringResource(R.string.delete_duplicates_confirm, totalCopies))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAllConfirm = false
                    viewModel.deleteAllDuplicates { deleted ->
                        Toast.makeText(
                            context,
                            "Deleted $deleted duplicate copies",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun formatDate(millis: Long): String {
    return try {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
    } catch (e: Exception) {
        ""
    }
}
