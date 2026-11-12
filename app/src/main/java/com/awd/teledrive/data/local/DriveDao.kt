package com.awd.teledrive.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import java.io.File

@Dao
interface DriveDao {
    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND (splitGroupId IS NULL OR partIndex = 0) AND virtualParentId = :virtualParentId ORDER BY createdAt DESC")
    fun getItemsFlow(chatId: Long, virtualParentId: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND (splitGroupId IS NULL OR partIndex = 0) AND virtualParentId = :virtualParentId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getItemsPage(chatId: Long, virtualParentId: String, limit: Int, offset: Int): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND (splitGroupId IS NULL OR partIndex = 0) AND virtualParentId = :virtualParentId")
    fun getItemsByVirtualParent(chatId: Long, virtualParentId: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE isStarred = 1 AND virtualParentId != 'vf_safe_space' AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun getStarredItems(): Flow<List<DriveItemEntity>>

    @Query("UPDATE drive_items SET isStarred = :isStarred WHERE id = :id AND parentChatId = :chatId")
    suspend fun updateStarred(id: Long, chatId: Long, isStarred: Boolean)

    @Query("UPDATE drive_items SET name = :newName WHERE id = :id AND parentChatId = :chatId")
    suspend fun renameItem(id: Long, chatId: Long, newName: String)

    @Query("SELECT * FROM drive_items WHERE id = :id AND parentChatId = :chatId LIMIT 1")
    suspend fun getItemById(id: Long, chatId: Long): DriveItemEntity?

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND id IN (:ids)")
    suspend fun getItemsByIds(ids: List<Long>, chatId: Long): List<DriveItemEntity>

    @Query("SELECT * FROM drive_items WHERE remoteUniqueId = :uniqueId LIMIT 1")
    suspend fun getItemByUniqueId(uniqueId: String): DriveItemEntity?

    @Query("SELECT * FROM drive_items WHERE virtualId = :vId LIMIT 1")
    suspend fun getVirtualFolderById(vId: String): DriveItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<DriveItemEntity>)

    @Query("DELETE FROM drive_items WHERE isVirtual = 1 AND virtualId NOT IN (:ids)")
    suspend fun deleteVirtualFoldersNotInList(ids: List<String>)

    @Transaction
    suspend fun syncVirtualFolders(entities: List<DriveItemEntity>) {
        val virtualIds = entities.mapNotNull { it.virtualId }
        deleteVirtualFoldersNotInList(virtualIds)
        insertItems(entities)
    }

    @Transaction
    suspend fun refreshChatItems(chatId: Long, items: List<DriveItemEntity>, preserveFolders: Boolean = false) {
        val newItemIds = items.map { it.id }.toSet()
        val existingItems = getItemsSync(chatId)
        val existingMap = existingItems.associateBy { it.id }
        // Content hashes remembered from earlier uploads survive the
        // re-sync (mapMessageToEntity creates fresh rows with null hash).
        val hashByUniqueId = getFileHashesByUniqueIds(items.mapNotNull { it.remoteUniqueId })

        val updatedItems = items.map { item ->
            val existing = existingMap[item.id]
            val savedLocalPath = item.localPath ?: existing?.localPath?.takeIf { File(it).exists() }
            val savedThumbPath = item.thumbnailPath ?: existing?.thumbnailPath?.takeIf { File(it).exists() }
            val preservedParent = if (existing?.virtualParentId == "vf_safe_space") "vf_safe_space" else item.virtualParentId
            item.copy(
                isStarred = existing?.isStarred == true,
                localPath = savedLocalPath,
                thumbnailPath = savedThumbPath,
                virtualParentId = preservedParent,
                contentHash = existing?.contentHash ?: hashByUniqueId[item.remoteUniqueId]
            )
        }

        val toDelete = existingItems.filter { item ->
            item.id !in newItemIds && (!preserveFolders || !item.isFolder) && !item.isVirtual
        }.map { it.id }
        
        if (toDelete.isNotEmpty()) {
            deleteItemsByIds(chatId, toDelete)
        }
        insertItems(updatedItems)
    }

    @Transaction
    suspend fun insertItemsPreservingStarred(items: List<DriveItemEntity>) {
        // P1-19: একবারে ব্যাচ লোড — প্রতি আইটেমে আলাদা getItemById (N+1)
        // নয়; চ্যাট ধরে গ্রুপ করে একটি করে WHERE id IN (...) কোয়েরি।
        val existingMap = items.groupBy { it.parentChatId }.flatMap { (chatId, group) ->
            getItemsByIds(group.map { it.id }, chatId)
        }.associateBy { it.id to it.parentChatId }
        // Content hashes remembered from earlier uploads (see above).
        val hashByUniqueId = getFileHashesByUniqueIds(items.mapNotNull { it.remoteUniqueId })
        val updatedItems = items.map { item ->
            val existing = existingMap[item.id to item.parentChatId]
            val savedLocalPath = item.localPath ?: existing?.localPath?.takeIf { File(it).exists() }
            val savedThumbPath = item.thumbnailPath ?: existing?.thumbnailPath?.takeIf { File(it).exists() }
            val preservedParent = if (existing?.virtualParentId == "vf_safe_space") "vf_safe_space" else item.virtualParentId
            item.copy(
                isStarred = existing?.isStarred == true,
                localPath = savedLocalPath,
                thumbnailPath = savedThumbPath,
                virtualParentId = preservedParent,
                contentHash = existing?.contentHash ?: hashByUniqueId[item.remoteUniqueId]
            )
        }
        insertItems(updatedItems)
    }

    @Query("UPDATE drive_items SET virtualParentId = :parentId WHERE id = :id")
    suspend fun updateVirtualParent(id: Long, parentId: String)

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId")
    suspend fun getItemsSync(chatId: Long): List<DriveItemEntity>

    @Query("SELECT * FROM drive_items WHERE virtualParentId = :virtualId")
    suspend fun getItemsByVirtualParentSync(virtualId: String): List<DriveItemEntity>

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId AND id IN (:ids)")
    suspend fun deleteItemsByIds(chatId: Long, ids: List<Long>)

    @Query("UPDATE drive_items SET localPath = :path WHERE remoteUniqueId = :uniqueId")
    suspend fun updateLocalPathByUniqueId(uniqueId: String, path: String?)

    @Query("UPDATE drive_items SET thumbnailPath = :path WHERE remoteUniqueId = :uniqueId OR thumbnailRemoteUniqueId = :uniqueId")
    suspend fun updateThumbnailPathByUniqueId(uniqueId: String, path: String?)

    @Query("UPDATE drive_items SET localPath = :path WHERE telegramFileId = :fileId")
    suspend fun updateLocalPath(fileId: Int, path: String)

    /**
     * Single call replacing the three separate local-path updates previously
     * done at call sites: always updates by telegram file id, and when a
     * remote unique id is known also updates the local and thumbnail paths
     * matched by that unique id.
     */
    @Transaction
    suspend fun updateFileLocalPaths(fileId: Int, uniqueId: String, path: String) {
        updateLocalPath(fileId, path)
        if (uniqueId.isNotEmpty()) {
            updateLocalPathByUniqueId(uniqueId, path)
            updateThumbnailPathByUniqueId(uniqueId, path)
        }
    }

    @Query("UPDATE drive_items SET localPath = NULL")
    suspend fun clearAllLocalPaths()

    @Query("UPDATE drive_items SET virtualParentId = '0' WHERE isVirtual = 0")
    suspend fun resetAllFileVirtualParents()

    @Query("UPDATE drive_items SET thumbnailPath = NULL")
    suspend fun clearAllThumbnailPaths()

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId")
    suspend fun deleteItemsByChat(chatId: Long)

    @Query("DELETE FROM drive_items WHERE id = :id AND parentChatId = :chatId")
    suspend fun deleteItemCompletely(id: Long, chatId: Long)

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId AND id NOT IN (:ids) AND isFolder = 0 AND isVirtual = 0")
    suspend fun deleteFilesByChatNotInList(chatId: Long, ids: List<Long>)

    @Query("DELETE FROM drive_items WHERE id < 0")
    suspend fun deletePendingItems()

    @Query("SELECT * FROM drive_items WHERE name LIKE '%' || :query || '%' AND virtualParentId != 'vf_safe_space' AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun searchGlobal(query: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE name LIKE '%' || :query || '%' AND virtualParentId != 'vf_safe_space' AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun searchGlobalPage(query: String, limit: Int, offset: Int): Flow<List<DriveItemEntity>>

    // ---- Batch 7b smart search ----

    /**
     * One row of a smart-search result. `matchedInContent` is 1 when the
     * hit came from file_content_fts (file text) rather than the file name,
     * so the UI can mark it ("in file text").
     */
    data class SmartSearchRow(
        @Embedded val item: DriveItemEntity,
        val matchedInContent: Int
    )

    /**
     * Combined name-FTS + content-FTS + filters search. The SQL is built
     * by DriveRepository (SimpleSQLiteQuery, bound `?` args only — user
     * input is never concatenated into SQL) and always selects
     * `d.*, <0|1> AS matchedInContent` from drive_items, ordered folders
     * first then newest first, capped at 200 rows. Observed tables are
     * drive_items (name FTS is trigger-synced from it) and
     * file_content_fts (re-indexing re-runs the search).
     */
    @RawQuery(observedEntities = [DriveItemEntity::class, FileContentFts::class])
    fun smartSearchRaw(query: SupportSQLiteQuery): Flow<List<SmartSearchRow>>

    // ---- Batch 7b file-content index (standalone FTS, manual) ----

    @Query("SELECT contentText FROM file_content_fts WHERE remoteUniqueId = :uniqueId LIMIT 1")
    suspend fun getFileContentText(uniqueId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileContent(row: FileContentFts)

    @Query("DELETE FROM file_content_fts WHERE remoteUniqueId = :uniqueId")
    suspend fun deleteFileContent(uniqueId: String)

    /**
     * file_content_fts has no UNIQUE constraint on remoteUniqueId (FTS
     * tables have none), so REPLACE would not dedupe — delete first, then
     * insert, in one transaction. At most one row per file.
     */
    @Transaction
    suspend fun upsertFileContent(row: FileContentFts) {
        deleteFileContent(row.remoteUniqueId)
        insertFileContent(row)
    }

    // ---- Batch 7b recent searches ----

    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT 10")
    fun getRecentSearches(): Flow<List<RecentSearch>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentSearch(search: RecentSearch)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun deleteRecentSearch(query: String)

    @Query("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY timestamp DESC LIMIT 10)")
    suspend fun trimRecentSearches()

    /**
     * Records a query (REPLACE bumps its timestamp) and trims the table
     * to the 10 newest rows.
     */
    @Transaction
    suspend fun saveRecentSearch(query: String) {
        insertRecentSearch(RecentSearch(query.trim(), System.currentTimeMillis()))
        trimRecentSearches()
    }

    // ---- Vault 2.0 encrypt-on-hide (Batch 7b vault side) ----

    @Query("UPDATE drive_items SET localPath = :encryptedPath, isEncrypted = 1 WHERE id = :itemId AND parentChatId = :chatId")
    suspend fun markLocalCopyEncrypted(itemId: Long, chatId: Long, encryptedPath: String)

    @Query("SELECT * FROM drive_items WHERE isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun getAllFiles(): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getAllFilesPage(limit: Int, offset: Int): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE (virtualParentId = 'vf_safe_space' OR isEncrypted = 1 OR isSecure = 1) AND isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0)")
    fun getSafeFolderFiles(): Flow<List<DriveItemEntity>>

    @Query("SELECT SUM(size) FROM drive_items WHERE isFolder = 0")
    fun getTotalSize(): Flow<Long?>

    data class FileSizeRow(
        val id: Long,
        val size: Long,
        val splitGroupId: String?,
        val totalParts: Int
    )

    @Query("SELECT id, size, splitGroupId, totalParts FROM drive_items WHERE isFolder = 0")
    fun getFileSizeRows(): Flow<List<FileSizeRow>>

    @Query("SELECT * FROM drive_items WHERE splitGroupId = :groupId ORDER BY partIndex ASC")
    suspend fun getSplitFileParts(groupId: String): List<DriveItemEntity>

    data class FileTypeStat(
        val category: String,
        val count: Int,
        val totalSize: Long
    )

    @Query("""
        SELECT 
            CASE 
                WHEN mimeType LIKE 'image/%' THEN 'IMAGE'
                WHEN mimeType LIKE 'video/%' THEN 'VIDEO'
                WHEN mimeType LIKE 'audio/%' THEN 'AUDIO'
                WHEN mimeType LIKE 'application/pdf' 
                     OR mimeType LIKE 'text/%' 
                     OR mimeType LIKE '%msword%' 
                     OR mimeType LIKE '%document%' 
                     OR mimeType LIKE '%sheet%' 
                     OR mimeType LIKE '%presentation%'
                     OR mimeType LIKE '%zip%'
                     OR mimeType LIKE '%rar%'
                     OR mimeType LIKE '%archive%'
                     OR mimeType LIKE '%7z%'
                THEN 'DOCUMENT'
                ELSE 'OTHER'
            END as category,
            COUNT(*) as count,
            SUM(size) as totalSize
        FROM drive_items 
        WHERE isFolder = 0 
        GROUP BY category
    """)
    fun getCloudFileTypeStats(): Flow<List<FileTypeStat>>

    // ---- Storage analyzer / duplicate finder ----

    @Query("SELECT * FROM drive_items WHERE isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY size DESC LIMIT :limit")
    fun getLargestFiles(limit: Int): Flow<List<DriveItemEntity>>

    data class DuplicateGroup(
        val contentHash: String,
        val size: Long,
        val count: Int,
        val wastedBytes: Long
    )

    @Query("""
        SELECT contentHash, size, COUNT(*) as count, (COUNT(*) - 1) * size as wastedBytes
        FROM drive_items
        WHERE contentHash IS NOT NULL AND isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0)
        GROUP BY contentHash, size HAVING COUNT(*) > 1 ORDER BY wastedBytes DESC
    """)
    fun getDuplicateGroups(): Flow<List<DuplicateGroup>>

    @Query("SELECT * FROM drive_items WHERE contentHash = :hash AND size = :size AND isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    suspend fun getDuplicateGroupItems(hash: String, size: Long): List<DriveItemEntity>

    @Query("SELECT * FROM drive_items WHERE isFolder = 0 AND contentHash IS NULL AND localPath IS NOT NULL AND (splitGroupId IS NULL OR partIndex = 0)")
    suspend fun getUnhashedDownloadedFiles(): List<DriveItemEntity>

    @Query("UPDATE drive_items SET contentHash = :hash WHERE remoteUniqueId = :uniqueId")
    suspend fun updateContentHash(uniqueId: String, hash: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFileHash(hash: FileHashEntity)

    @Query("SELECT * FROM file_hashes WHERE remoteUniqueId IN (:ids)")
    suspend fun getFileHashes(ids: List<String>): List<FileHashEntity>

    /**
     * Hashes for the given remote unique ids, looked up in chunks so a
     * large sync never exceeds SQLite's variable limit.
     */
    @Transaction
    suspend fun getFileHashesByUniqueIds(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        ids.distinct().chunked(400).forEach { chunk ->
            getFileHashes(chunk).forEach { result[it.remoteUniqueId] = it.contentHash }
        }
        return result
    }
}
