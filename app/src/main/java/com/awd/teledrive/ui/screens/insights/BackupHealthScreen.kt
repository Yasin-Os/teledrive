package com.awd.teledrive.ui.screens.insights

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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupHealthScreen(
    onBack: () -> Unit,
    viewModel: BackupHealthViewModel = hiltViewModel()
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRetrying by viewModel.isRetrying.collectAsState()
    val nextBackupTime by viewModel.nextBackupTime.collectAsState()
    val isAutoBackupEnabled by viewModel.isAutoBackupEnabled.collectAsState()
    val isBackupWifiOnly by viewModel.isBackupWifiOnly.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_health)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
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
            val snap = snapshot
            if (snap == null) {
                Text(
                    text = stringResource(R.string.status_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                return@Column
            }

            // Summary card: last *successful* backup, this month's data, next run.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SummaryRow(
                        label = stringResource(R.string.last_successful_backup),
                        value = snap.lastSuccessfulBackupTime?.let { formatDateTime(it) }
                            ?: stringResource(R.string.never_backed_up)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow(
                        label = stringResource(R.string.data_this_month),
                        value = FileUtils.formatSize(snap.monthlyBackupBytes)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow(
                        label = stringResource(R.string.next_backup),
                        value = nextBackupTime?.let { formatDateTime(it) }
                            ?: stringResource(R.string.not_scheduled)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SummaryRow(
                        label = stringResource(R.string.auto_backup),
                        value = if (isAutoBackupEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)
                    )
                    if (isBackupWifiOnly) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SummaryRow(
                            label = stringResource(R.string.backup_wifi_only),
                            value = stringResource(R.string.enabled)
                        )
                    }
                }
            }

            // Per-folder status.
            Text(
                text = stringResource(R.string.backup_folders),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (snap.folders.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_backup_folders_set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                snap.folders.forEach { folder ->
                    ListItem(
                        headlineContent = {
                            Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.backed_up_count, folder.backedUpCount) +
                                    " • " + stringResource(R.string.pending_count, folder.pendingCount) +
                                    " • " + stringResource(R.string.failed_count, folder.failedCount),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }

            // Failed files + retry.
            if (snap.failedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.failed_files),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { viewModel.retryAllFailed() },
                        enabled = !isRetrying
                    ) {
                        Text(stringResource(R.string.retry_all))
                    }
                }
                snap.failedFiles.forEach { file ->
                    ListItem(
                        headlineContent = {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                text = FileUtils.formatSize(file.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            OutlinedButton(
                                onClick = { viewModel.retryFailed(file.key) },
                                enabled = !isRetrying
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    )
                }
                HorizontalDivider()
            }

            // Pending files + manual backup.
            if (snap.pendingFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.pending_files),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { viewModel.triggerManualBackup() }) {
                        Text(stringResource(R.string.backup_now))
                    }
                }
                snap.pendingFiles.take(10).forEach { file ->
                    ListItem(
                        headlineContent = {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                text = FileUtils.formatSize(file.size),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
                if (snap.pendingFiles.size > 10) {
                    Text(
                        text = "+ ${snap.pendingFiles.size - 10} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun formatDateTime(millis: Long): String {
    return try {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
    } catch (e: Exception) {
        ""
    }
}
