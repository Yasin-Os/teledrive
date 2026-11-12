package com.awd.teledrive.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Batch 7b smart search (v7 -> v8): FTS name index, file-content index
 * and recent searches.
 *
 * The drive_items_fts SQL below is byte-for-byte what Room 2.8.4
 * generates for `@Fts4(contentEntity = DriveItemEntity::class)` on
 * `DriveItemFts(name: String)` — verified against the Room 2.8.4
 * compiler sources (FtsEntity.createTableQuery / createSyncTriggers,
 * Property.databaseDefinition). Three details matter for migration
 * validation (Room compares the parsed FTS options, not the raw string):
 *  - `name` is `TEXT NOT NULL` (non-null Kotlin String);
 *  - the content option uses BACKTICKS (`` content=`drive_items` ``) —
 *    single quotes would parse to a different option string and fail
 *    validation with "Migration didn't properly handle";
 *  - Room 2.8.4 does NOT emit a `content_rowid` option (SQLite defaults
 *    it to `rowid`, which is what the triggers use), so none is written
 *    here either.
 * The four trigger statements are likewise Room's generated ones
 * (DELETE ... WHERE `docid`=OLD.`rowid`, INSERT (`docid`, `name`)
 * VALUES (NEW.`rowid`, NEW.`name`)). Room drops and re-creates these
 * triggers itself around migrations, so the names must match Room's
 * `room_fts_content_sync_<table>_<op>` pattern exactly.
 * The backfill INSERT SELECT is required: triggers only fire on future
 * writes, so without it every pre-existing file would be invisible to
 * name search until its row is re-written.
 *
 * REGISTRATION: DatabaseModule registers MIGRATION_5_6, MIGRATION_6_7
 * and MIGRATION_7_8 in both builders — without the 7->8 path Room's
 * fallbackToDestructiveMigration(true) would WIPE the database on upgrade.
 *
 * Renumbered at rebase: PR #13 (Batch 7a) merged first with its own
 * Migration(6, 7), so this one became Migration(7, 8) and the database
 * version is 8 (migration chain 6 -> 7 -> 8).
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. External-content FTS4 mirror of drive_items.name.
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `drive_items_fts` " +
                "USING FTS4(`name` TEXT NOT NULL, content=`drive_items`)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_drive_items_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `drive_items` BEGIN " +
                "DELETE FROM `drive_items_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_drive_items_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `drive_items` BEGIN " +
                "DELETE FROM `drive_items_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_drive_items_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `drive_items` BEGIN " +
                "INSERT INTO `drive_items_fts`(`docid`, `name`) " +
                "VALUES (NEW.`rowid`, NEW.`name`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_drive_items_fts_AFTER_INSERT " +
                "AFTER INSERT ON `drive_items` BEGIN " +
                "INSERT INTO `drive_items_fts`(`docid`, `name`) " +
                "VALUES (NEW.`rowid`, NEW.`name`); END"
        )
        // Backfill: index every file that already exists.
        db.execSQL(
            "INSERT INTO `drive_items_fts`(`docid`, `name`) " +
                "SELECT `rowid`, `name` FROM `drive_items`"
        )
        // 2. Standalone FTS4 table for indexed file text (managed manually
        // via DriveDao; same NOT NULL columns Room generates for
        // FileContentFts).
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `file_content_fts` " +
                "USING FTS4(`remoteUniqueId` TEXT NOT NULL, `contentText` TEXT NOT NULL)"
        )
        // 3. Recent search queries.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recent_searches` " +
                "(`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))"
        )
    }
}

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

@Database(
    entities = [
        DriveItemEntity::class,
        TransferEntity::class,
        FileHashEntity::class,
        DriveItemFts::class,
        FileContentFts::class,
        RecentSearch::class
    ],
    version = 8,
    exportSchema = false
)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun driveDao(): DriveDao
    abstract fun transferDao(): TransferDao
}
