package com.awd.teledrive.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.MIGRATION_5_6
import com.awd.teledrive.data.local.MIGRATION_6_7
import com.awd.teledrive.data.local.MIGRATION_7_8
import com.awd.teledrive.data.local.TeleDriveDatabase
import com.awd.teledrive.data.local.TransferDao
import com.awd.teledrive.data.secure.SecureSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"
    private const val LEGACY_PLAIN_DB_NAME = "teledrive_no_enc.db"
    private const val ENCRYPTED_DB_NAME = "teledrive_enc.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secureSettings: SecureSettings
    ): TeleDriveDatabase {
        // The native SQLCipher library must be loaded before any SQLCipher API
        // (including SupportOpenHelperFactory) is used. M2: a missing native
        // library must not crash the whole launch — a plaintext database needs
        // no SQLCipher at all, so remember the failure and only fail when an
        // encrypted database is actually needed below.
        val sqlcipherAvailable: Boolean = try {
            System.loadLibrary("sqlcipher")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load SQLCipher native library", e)
            false
        }

        val plainFile = context.getDatabasePath(LEGACY_PLAIN_DB_NAME)
        val encryptedFile = context.getDatabasePath(ENCRYPTED_DB_NAME)

        if (plainFile.exists() && plainFile.length() == 0L) {
            // Room created the file but never initialised it: nothing to migrate.
            deleteDatabaseFiles(plainFile)
        }

        if (!sqlcipherAvailable) {
            // No SQLCipher, no encrypted database — but the legacy plaintext
            // database opens with plain Room and needs no native library.
            if (plainFile.exists()) {
                Log.e(TAG, "SQLCipher unavailable: opening legacy plaintext database")
                return buildPlainDatabase(context)
            }
            throw IllegalStateException("SQLCipher native library unavailable: cannot open the encrypted database")
        }

        val passphrase: ByteArray? = obtainDatabasePassphrase(secureSettings)

        if (passphrase == null) {
            // Fail-closed for new installs (no key = no encrypted DB), but never
            // lock existing users out of their plaintext data: if a plaintext
            // DB exists, keep opening it unencrypted — even when an encrypted
            // copy also exists (interrupted migration). The encrypted copy is
            // left untouched, so the next launch with working secure storage
            // resumes the normal reconcile/migration path (H23).
            if (plainFile.exists()) {
                Log.e(TAG, "Opening legacy plaintext database: secure storage unavailable")
                return buildPlainDatabase(context)
            }
            throw IllegalStateException("Secure storage unavailable: cannot open the encrypted database")
        }

        // An encrypted database left behind by the pre-F4 migration has
        // user_version 0 (sqlcipher_export does not copy it): repair it
        // before Room sees it, or Room runs onCreate() and crashes on the
        // contentHash index (C6). A no-op when the version is already set.
        if (encryptedFile.exists()) {
            repairEncryptedUserVersion(encryptedFile, passphrase)
        }

        val useEncrypted: Boolean = when {
            encryptedFile.exists() && !plainFile.exists() -> true
            !encryptedFile.exists() && !plainFile.exists() -> true // fresh install
            !encryptedFile.exists() && plainFile.exists() ->
                migratePlaintextToEncrypted(plainFile, encryptedFile, passphrase)
            else -> reconcileDuplicateDatabases(plainFile, encryptedFile, passphrase)
        }

        return if (useEncrypted) {
            buildEncryptedDatabase(context, passphrase)
        } else {
            buildPlainDatabase(context)
        }
    }

    /**
     * H23: the Keystore can be briefly unavailable right after boot or during
     * a transient hiccup, and giving up on the first try used to crash the
     * app for every encrypted-DB user. Retry a few times first; only a
     * persistent failure returns null. Worst case this waits ~1s.
     */
    private fun obtainDatabasePassphrase(secureSettings: SecureSettings): ByteArray? {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return secureSettings.getOrCreateDatabasePassphrase()
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    runCatching { Thread.sleep(500L) }
                }
            }
        }
        Log.e(TAG, "Secure storage unavailable, cannot obtain database passphrase", lastError)
        return null
    }

    /**
     * C6 repair: databases migrated by the pre-F4 code have PRAGMA
     * user_version 0 because sqlcipher_export does not copy it. Room then
     * treats the file as brand new, runs onCreate() instead of the
     * migrations, and crashes creating the contentHash index on the old
     * (v5/v6) schema. If the version is 0, infer it from the schema and set
     * it, so Room runs the proper 5 -> 6 -> 7 -> 8 chain. Never touches a
     * database whose version is already set, and never deletes anything.
     */
    private fun repairEncryptedUserVersion(encryptedFile: File, passphrase: ByteArray) {
        try {
            SQLiteDatabase.openDatabase(
                encryptedFile.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READWRITE, null
            ).use { db ->
                val version = db.rawQuery("PRAGMA user_version", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                if (version != 0) return
                val inferred = inferSchemaVersion(db) ?: run {
                    Log.w(TAG, "Encrypted database has user_version 0 with an unrecognised schema; leaving it for Room to report")
                    return
                }
                db.execSQL("PRAGMA user_version = $inferred")
                Log.i(TAG, "Repaired encrypted database user_version 0 -> $inferred (pre-F4 migration)")
            }
        } catch (e: Exception) {
            // Wrong key, corrupt file, or unreadable database: leave it alone;
            // Room will report the real error as before.
            Log.e(TAG, "Could not inspect encrypted database user_version, leaving it unchanged", e)
        }
    }

    /**
     * Infers a Room schema version from the tables/columns/indexes present,
     * for databases whose user_version was lost. Returns null for
     * inconsistent schemas (those are left for Room to report loudly).
     */
    private fun inferSchemaVersion(db: SQLiteDatabase): Int? {
        val hasContentHash = db.rawQuery("PRAGMA table_info(drive_items)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "contentHash") found = true
            }
            found
        }
        fun tableExists(name: String): Boolean {
            db.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(name)
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0) > 0
            }
            return false
        }
        fun indexExists(name: String): Boolean {
            db.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf(name)
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0) > 0
            }
            return false
        }
        val hasFileHashes = tableExists("file_hashes")
        val hasFts = tableExists("drive_items_fts") &&
            tableExists("file_content_fts") && tableExists("recent_searches")
        return when {
            hasContentHash && hasFileHashes && hasFts -> 8
            hasContentHash && hasFileHashes -> 7
            !hasContentHash && indexExists("index_drive_items_parentChatId") -> 6
            !hasContentHash -> 5
            else -> null // contentHash without file_hashes: inconsistent, do not guess
        }
    }

    private fun buildEncryptedDatabase(context: Context, passphrase: ByteArray): TeleDriveDatabase {
        return Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            ENCRYPTED_DB_NAME
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            // M2: no destructive fallback. All migrations 5 -> 6 -> 7 -> 8
            // are registered, so a future version bump without a migration
            // must fail loudly at open instead of silently wiping the user's
            // library, starred flags, hashes and transfer history.
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8) // v7 -> v8: smart-search FTS tables + recent searches
            .build()
    }

    private fun buildPlainDatabase(context: Context): TeleDriveDatabase {
        return Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            LEGACY_PLAIN_DB_NAME
        )
            // Same as the encrypted builder: v5 -> v6 goes through the
            // migration and keeps data. No destructive fallback (M2): a
            // missing migration must fail loudly, never wipe silently.
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
            .addMigrations(MIGRATION_7_8) // v7 -> v8: smart-search FTS tables + recent searches
            .build()
    }

    /** Tables whose row counts must match after a plaintext -> encrypted copy (L5). */
    private val MIGRATION_TABLES = listOf(
        "drive_items", "transfers", "file_hashes",
        "drive_items_fts", "file_content_fts", "recent_searches"
    )

    /**
     * Row count of [table], or null when the table does not exist in this
     * database (pre-v7 files have no file_hashes; pre-v8 files have no FTS
     * tables or recent_searches). FTS virtual tables appear in sqlite_master
     * with type 'table' and support SELECT count(*).
     */
    private fun countRowsIfExists(db: SQLiteDatabase, table: String): Long? {
        db.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?",
            arrayOf(table)
        ).use { cursor ->
            if (cursor.moveToFirst() && cursor.getLong(0) == 0L) return null
        }
        return countRows(db, table)
    }

    /**
     * L5: compares row counts for EVERY entity table, not just drive_items
     * and transfers. A table present on one side but missing on the other
     * (or with a different count) fails verification. Returns false with a
     * log line on the first mismatch.
     */
    private fun verifyMigrationCounts(expected: Map<String, Long?>, actualDb: SQLiteDatabase): Boolean {
        for (table in MIGRATION_TABLES) {
            val expectedCount = expected[table]
            val actualCount = countRowsIfExists(actualDb, table)
            if (expectedCount != actualCount) {
                Log.e(TAG, "Migration verification failed: $table $actualCount != $expectedCount")
                return false
            }
        }
        return true
    }

    private fun countRows(db: SQLiteDatabase, table: String): Long {
        db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
    }

    /**
     * Migrates the existing plaintext database into a new SQLCipher-encrypted
     * file with sqlcipher_export, then verifies the row counts match before
     * deleting the plaintext original. Returns true only if the encrypted
     * database is verified complete. On any failure the plaintext database is
     * left untouched and the partial encrypted file is removed, so the next
     * launch retries from intact data.
     */
    private fun migratePlaintextToEncrypted(
        plainFile: File,
        encryptedFile: File,
        passphrase: ByteArray
    ): Boolean {
        Log.i(TAG, "Migrating plaintext database to encrypted database")
        var plainDb: SQLiteDatabase? = null
        try {
            plainDb = SQLiteDatabase.openDatabase(
                plainFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            val expectedCounts = MIGRATION_TABLES.associateWith { countRowsIfExists(plainDb, it) }
            // C6: sqlcipher_export does NOT copy PRAGMA user_version, so
            // remember the plaintext version here and restore it on the
            // encrypted copy below.
            val plainVersion = plainDb.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            // The passphrase bytes are bound as a blob, so the ATTACH key
            // derivation is identical to SupportOpenHelperFactory's byte[]
            // password (PBKDF2 over the same bytes).
            plainDb.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf<Any>(encryptedFile.absolutePath, passphrase)
            )
            plainDb.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            plainDb.execSQL("DETACH DATABASE encrypted")
            plainDb.close()
            plainDb = null

            SQLiteDatabase.openDatabase(
                encryptedFile.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READWRITE, null
            ).use { encDb ->
                // C6: restore the version sqlcipher_export dropped, so Room
                // runs the migrations instead of onCreate() (which crashes
                // creating the contentHash index on the old schema).
                if (plainVersion > 0) {
                    encDb.execSQL("PRAGMA user_version = $plainVersion")
                }
                if (!verifyMigrationCounts(expectedCounts, encDb)) {
                    throw IllegalStateException("Migration verification failed: row counts do not match")
                }
            }
            deleteDatabaseFiles(plainFile)
            Log.i(TAG, "Database migration complete: ${expectedCounts["drive_items"]} items, ${expectedCounts["transfers"]} transfers")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Database migration failed, keeping plaintext database", e)
            deleteDatabaseFiles(encryptedFile)
            return false
        } finally {
            try {
                plainDb?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Both files exist: a previous migration was interrupted. If the encrypted
     * copy is complete, drop the plaintext original; if it is partial, remove
     * it and re-migrate from the intact plaintext database. Returns true if
     * the encrypted database is the copy to use.
     */
    private fun reconcileDuplicateDatabases(
        plainFile: File,
        encryptedFile: File,
        passphrase: ByteArray
    ): Boolean {
        return try {
            val expectedCounts: Map<String, Long?>
            SQLiteDatabase.openDatabase(
                plainFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { plainDb ->
                expectedCounts = MIGRATION_TABLES.associateWith { countRowsIfExists(plainDb, it) }
            }
            var encryptedComplete = false
            SQLiteDatabase.openDatabase(
                encryptedFile.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null
            ).use { encDb ->
                encryptedComplete = verifyMigrationCounts(expectedCounts, encDb)
            }
            if (encryptedComplete) {
                deleteDatabaseFiles(plainFile)
                true
            } else {
                Log.w(TAG, "Encrypted database is incomplete, re-migrating from plaintext database")
                deleteDatabaseFiles(encryptedFile)
                migratePlaintextToEncrypted(plainFile, encryptedFile, passphrase)
            }
        } catch (e: Exception) {
            // C6/M2: a blanket "use the encrypted one" here turned a corrupt
            // encrypted copy (or a wrong key) into a permanent startup crash
            // loop. If the plaintext database is still readable, it is the
            // safe copy: use it and let the next launch retry the migration.
            // Only when the plaintext is unreadable too do we keep the old
            // behaviour (try the encrypted copy).
            Log.e(TAG, "Could not reconcile duplicate databases", e)
            val plainReadable = try {
                SQLiteDatabase.openDatabase(
                    plainFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                ).use { plainDb ->
                    countRows(plainDb, "drive_items")
                }
                true
            } catch (_: Exception) {
                false
            }
            if (plainReadable) {
                Log.w(TAG, "Plaintext database is readable; using it instead of the unusable encrypted copy")
                false
            } else {
                Log.e(TAG, "Neither database reconciled; keeping both and using the encrypted one", e)
                true
            }
        }
    }

    private fun deleteDatabaseFiles(dbFile: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            try {
                File(dbFile.absolutePath + suffix).delete()
            } catch (_: Exception) {
            }
        }
    }

    @Provides
    fun provideDriveDao(database: TeleDriveDatabase): DriveDao {
        return database.driveDao()
    }

    @Provides
    fun provideTransferDao(database: TeleDriveDatabase): TransferDao {
        return database.transferDao()
    }
}
