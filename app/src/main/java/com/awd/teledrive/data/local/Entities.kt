package com.awd.teledrive.data.local

import androidx.room.Entity
import androidx.room.Fts4
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
        Index(value = ["isStarred"], name = "index_drive_items_isStarred"),
        Index(value = ["contentHash"], name = "index_drive_items_contentHash")
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
    val isEncrypted: Boolean = false,
    // SHA-256 hex of the file content, filled at upload time (or by the
    // duplicate-finder scan for downloaded files). Null for files whose
    // content was never hashed (legacy items, split parts, secure files).
    val contentHash: String? = null
)

/**
 * Content hash of an uploaded file, keyed by the Telegram remote unique id.
 * Kept in its own table so the hash survives history re-syncs (which rebuild
 * DriveItemEntity rows): the DAO fills DriveItemEntity.contentHash from here
 * whenever items are inserted.
 */
@Entity(tableName = "file_hashes", primaryKeys = ["remoteUniqueId"])
data class FileHashEntity(
    val remoteUniqueId: String,
    val contentHash: String,
    val size: Long,
    val updatedAt: Long = System.currentTimeMillis()
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

// Batch 7b smart search: external-content FTS4 mirror of drive_items.name.
// Room keeps it in sync with drive_items through generated triggers
// (room_fts_content_sync_drive_items_fts_*); writes must go to
// drive_items, never to this table directly. Only `name` is mirrored —
// an FTS content table can only mirror columns that exist on the
// content entity (DriveItemEntity).
@Entity(tableName = "drive_items_fts")
@Fts4(contentEntity = DriveItemEntity::class)
data class DriveItemFts(
    val name: String
)

// Batch 7b smart search: standalone FTS4 table for indexed file text.
// NOT a content table — managed manually via DriveDao (delete-then-insert
// per remoteUniqueId). One row per file at most; a file with no local
// copy, an encrypted file, or a non-text file has no row here.
// PDFs have no row: the app has no PDF text-extraction library
// (PdfRenderer only renders bitmaps), so PDFs are searchable by name only.
@Entity(tableName = "file_content_fts")
@Fts4
data class FileContentFts(
    val remoteUniqueId: String,
    val contentText: String
)

// Batch 7b smart search: recent search queries, newest first.
// `query` is the primary key so re-running a query bumps it instead of
// duplicating it; DriveDao trims the table to the 10 newest rows.
@Entity(tableName = "recent_searches")
data class RecentSearch(
    @androidx.room.PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)
