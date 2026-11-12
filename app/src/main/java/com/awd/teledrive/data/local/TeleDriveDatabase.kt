package com.awd.teledrive.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the P1-1 Room indexes on drive_items (parentChatId, virtualParentId,
 * remoteUniqueId, virtualId, splitGroupId, isStarred). The index names match
 * the explicit names declared on DriveItemEntity, so the schema Room expects
 * at version 6 is exactly what this migration creates.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_parentChatId ON drive_items (parentChatId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_virtualParentId ON drive_items (virtualParentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_remoteUniqueId ON drive_items (remoteUniqueId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_virtualId ON drive_items (virtualId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_splitGroupId ON drive_items (splitGroupId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_isStarred ON drive_items (isStarred)")
    }
}

/**
 * Adds DriveItemEntity.contentHash (nullable, so existing rows are untouched)
 * with its index, plus the file_hashes table that remembers content hashes
 * across history re-syncs. The index name and table columns match exactly
 * what Room expects for the version 7 schema.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE drive_items ADD COLUMN contentHash TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_items_contentHash ON drive_items (contentHash)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS file_hashes (" +
                "remoteUniqueId TEXT NOT NULL, " +
                "contentHash TEXT NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(remoteUniqueId))"
        )
    }
}

@Database(entities = [DriveItemEntity::class, TransferEntity::class, FileHashEntity::class], version = 7, exportSchema = false)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun driveDao(): DriveDao
    abstract fun transferDao(): TransferDao
}
