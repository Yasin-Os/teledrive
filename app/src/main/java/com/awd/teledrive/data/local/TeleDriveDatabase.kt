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

@Database(entities = [DriveItemEntity::class, TransferEntity::class], version = 6, exportSchema = false)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun driveDao(): DriveDao
    abstract fun transferDao(): TransferDao
}
