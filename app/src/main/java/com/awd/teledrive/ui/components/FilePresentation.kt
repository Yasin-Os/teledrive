package com.awd.teledrive.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.awd.teledrive.R
import com.awd.teledrive.domain.model.DriveItem
import com.awd.teledrive.ui.theme.FileTypeApk
import com.awd.teledrive.ui.theme.FileTypeArchive
import com.awd.teledrive.ui.theme.FileTypeAudio
import com.awd.teledrive.ui.theme.FileTypeFolderSecure
import com.awd.teledrive.ui.theme.FileTypeImage
import com.awd.teledrive.ui.theme.FileTypePdf
import com.awd.teledrive.ui.theme.FileTypeText
import com.awd.teledrive.ui.theme.FileTypeVideo

/**
 * Shared file icon + accent colour (P2-1). Previously duplicated in
 * HomeScreen and PreviewScreen with different palettes; both now use this
 * single implementation, based on HomeScreen's richer mapping (folders and
 * the full type mapping, including .apk / archive / text by extension).
 */
@Composable
fun getFileIconAndColor(item: DriveItem): Pair<ImageVector, Color> {
    if (item is DriveItem.Folder) {
        return if (item.isSecure) Icons.Default.Folder to FileTypeFolderSecure
        else Icons.Default.Folder to MaterialTheme.colorScheme.primary
    } else {
        val file = item as DriveItem.File
        val lowerName = file.name.lowercase()
        return when {
            file.mimeType.contains("android.package-archive") || lowerName.endsWith(".apk") -> Icons.Default.Android to FileTypeApk
            file.mimeType.contains("zip") || file.mimeType.contains("rar") || file.mimeType.contains("7z") || lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> Icons.Default.FolderZip to FileTypeArchive
            file.mimeType.startsWith("image/") -> Icons.Default.Image to FileTypeImage
            file.mimeType.startsWith("video/") -> Icons.Default.VideoFile to FileTypeVideo
            file.mimeType.startsWith("audio/") -> Icons.Default.AudioFile to FileTypeAudio
            file.mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> Icons.Default.PictureAsPdf to FileTypePdf
            file.mimeType.contains("text/") || lowerName.endsWith(".txt") -> Icons.AutoMirrored.Filled.Article to FileTypeText
            else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
}

/**
 * Shared overflow menu for a drive item (P2-1). Previously duplicated
 * (~60 lines each) in DriveListItem and DriveGridItem; both now use this
 * one. Menu items, order and visibility rules are identical to the originals.
 */
@Composable
fun DriveItemMenu(
    item: DriveItem,
    isDownloaded: Boolean,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onShareClick: () -> Unit,
    onStarClick: () -> Unit,
    onInfoClick: () -> Unit,
    onRenameClick: (() -> Unit)? = null,
    onSaveAsPhoneClick: (() -> Unit)? = null,
    onDownloadClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null
) {
    val isSecure = (item is DriveItem.Folder && item.isSecure) || (item is DriveItem.File && item.isEncrypted)
    val isVirtual = item is DriveItem.Folder && item.isVirtual
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        if (!isVirtual && !isSecure) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                onClick = { onShareClick(); onDismissRequest() },
                leadingIcon = { Icon(Icons.Default.Share, null) }
            )
        }
        if (!isSecure) {
            DropdownMenuItem(
                text = { Text(if (item.isStarred) stringResource(R.string.remove_star) else stringResource(R.string.add_star)) },
                onClick = { onStarClick(); onDismissRequest() },
                leadingIcon = { Icon(if (item.isStarred) Icons.Default.Star else Icons.Outlined.StarOutline, null) }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.info)) },
            onClick = { onInfoClick(); onDismissRequest() },
            leadingIcon = { Icon(Icons.Default.Info, null) }
        )
        onRenameClick?.let {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = { it(); onDismissRequest() },
                leadingIcon = { Icon(Icons.Default.Edit, null) }
            )
        }
        if (!isSecure) {
            if (isDownloaded && onSaveAsPhoneClick != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_as_phone)) },
                    onClick = { onSaveAsPhoneClick(); onDismissRequest() },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(if (item is DriveItem.File) stringResource(R.string.download) else stringResource(R.string.download_contents)) },
                    onClick = { onDownloadClick(); onDismissRequest() },
                    leadingIcon = { Icon(Icons.Default.Download, null) }
                )
            }
        }
        onDeleteClick?.let {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = { it(); onDismissRequest() },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}
