package com.awd.teledrive.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "drive_items",
    primaryKeys = ["id", "parentChatId"],
    indices = [
        Index(value = ["parentChatId"], name = "index_drive_items_parentChatId"),
        Index(value = ["virtualParentId"], name = "index_drive_items_virtualParentId"),
        Index(value = ["remoteUniqueId"], name = "index_drive_items_remoteUniqueId"),
        Index(value = ["virtualId"], name = "index_drive_items_virtualId"),
        Index(value = ["splitGroupId"], name = "index_drive_items_splitGroupId"),
        Index(value = ["isStarred"], name = "index_drive_items_isStarred")
    ]
)
data class DriveItemEntity(
    val id: Long,
    val name: String,
    val size: Long,
    val mimeType: String,
    val telegramFileId: Int,
    val parentChatId: Long,
    val isFolder: Boolean,
    val thumbnailPath: String? = null,
    val localPath: String? = null,
    val isStarred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnailFileId: Int? = null,
    val remoteUniqueId: String? = null,
    val thumbnailRemoteUniqueId: String? = null,
    val splitGroupId: String? = null,
    val partIndex: Int = 0,
    val totalParts: Int = 1,
    val isVirtual: Boolean = false,
    val virtualId: String? = null,
    val virtualParentId: String? = "0",
    val isSecure: Boolean = false,
    val isEncrypted: Boolean = false
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @androidx.room.PrimaryKey val remoteUniqueId: String,
    val fileId: Int,
    val fileName: String,
    val progress: Float,
    val isDownload: Boolean,
    val status: String,
    val totalSize: Long,
    val downloadedSize: Long,
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
