package com.awd.teledrive.ui.screens.insights

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R
import com.awd.teledrive.core.utils.FileUtils
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.screens.settings.AnalysisItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    onBack: () -> Unit,
    onNavigateToPreview: (DriveItem.File) -> Unit,
    onNavigateToDuplicateFinder: () -> Unit,
    viewModel: StorageAnalyzerViewModel = hiltViewModel()
) {
    val cloudStats by viewModel.cloudStats.collectAsState()
    val largestFiles by viewModel.largestFiles.collectAsState()
    val wastedBytes by viewModel.wastedBytes.collectAsState()
    val internalCacheSize by viewModel.internalCacheSize.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_analyzer)) },
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
            val totalSize = cloudStats.sumOf { it.totalSize }
            val totalCount = cloudStats.sumOf { it.count }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.cloud_usage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = FileUtils.formatSize(totalSize),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.items_count, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Free-up hint: duplicate waste + local cache.
            if (wastedBytes > 0L || internalCacheSize > 0L) {
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
                            text = stringResource(R.string.free_up_space),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (wastedBytes > 0L) {
                            Text(
                                text = stringResource(R.string.duplicates_wasted, FileUtils.formatSize(wastedBytes)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onNavigateToDuplicateFinder) {
                                Text(stringResource(R.string.duplicate_finder))
                            }
                        }
                        if (internalCacheSize > 0L) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Local cache: ${FileUtils.formatSize(internalCacheSize)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            Text(
                text = stringResource(R.string.cache_breakdown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )

            val photoStat = cloudStats.find { it.category == "IMAGE" }
            val videoStat = cloudStats.find { it.category == "VIDEO" }
            val audioStat = cloudStats.find { it.category == "AUDIO" }
            val docStat = cloudStats.find { it.category == "DOCUMENT" }
            val otherStat = cloudStats.find { it.category == "OTHER" }

            AnalysisItem(
                icon = Icons.Default.Image,
                label = stringResource(R.string.category_images),
                count = photoStat?.count ?: 0,
                size = photoStat?.totalSize ?: 0L,
                color = Color(0xFF4CAF50),
                description = stringResource(R.string.category_thumbnails_desc)
            )
            AnalysisItem(
                icon = Icons.Default.VideoFile,
                label = stringResource(R.string.category_videos),
                count = videoStat?.count ?: 0,
                size = videoStat?.totalSize ?: 0L,
                color = Color(0xFFE91E63),
                description = stringResource(R.string.category_videos_desc)
            )
            AnalysisItem(
                icon = Icons.Default.AudioFile,
                label = stringResource(R.string.category_audio),
                count = audioStat?.count ?: 0,
                size = audioStat?.totalSize ?: 0L,
                color = Color(0xFFFF9800),
                description = stringResource(R.string.category_audio_desc)
            )
            AnalysisItem(
                icon = Icons.Default.Description,
                label = stringResource(R.string.category_documents),
                count = docStat?.count ?: 0,
                size = docStat?.totalSize ?: 0L,
                color = Color(0xFF2196F3),
                description = stringResource(R.string.category_documents_desc)
            )
            AnalysisItem(
                icon = Icons.Default.FolderZip,
                label = stringResource(R.string.category_others),
                count = otherStat?.count ?: 0,
                size = otherStat?.totalSize ?: 0L,
                color = Color(0xFF9C27B0),
                description = stringResource(R.string.category_others_desc)
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.largest_files),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )
            Text(
                text = stringResource(R.string.tap_to_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (largestFiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                largestFiles.forEach { entity ->
                    ListItem(
                        headlineContent = {
                            Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(iconForMime(entity.mimeType), contentDescription = null)
                        },
                        trailingContent = {
                            Text(
                                text = FileUtils.formatSize(entity.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        modifier = Modifier.clickable {
                            onNavigateToPreview(viewModel.toFile(entity))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun iconForMime(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.VideoFile
    mimeType.startsWith("audio/") -> Icons.Default.AudioFile
    else -> Icons.Default.Description
}
