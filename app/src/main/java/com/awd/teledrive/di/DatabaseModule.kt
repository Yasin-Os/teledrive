package com.awd.teledrive.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.MIGRATION_5_6
import com.awd.teledrive.data.local.MIGRATION_6_7
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
        // (including SupportOpenHelperFactory) is used.
        System.loadLibrary("sqlcipher")

        val plainFile = context.getDatabasePath(LEGACY_PLAIN_DB_NAME)
        val encryptedFile = context.getDatabasePath(ENCRYPTED_DB_NAME)

        if (plainFile.exists() && plainFile.length() == 0L) {
            // Room created the file but never initialised it: nothing to migrate.
            deleteDatabaseFiles(plainFile)
        }

        val passphrase: ByteArray? = try {
            secureSettings.getOrCreateDatabasePassphrase()
        } catch (e: Exception) {
            Log.e(TAG, "Secure storage unavailable, cannot obtain database passphrase", e)
            null
        }

        if (passphrase == null) {
            // Fail-closed for new installs (no key = no encrypted DB), but never
            // lock existing users out of their plaintext data: if only a
            // plaintext DB exists, keep opening it unencrypted.
            if (!encryptedFile.exists() && plainFile.exists()) {
                Log.e(TAG, "Opening legacy plaintext database: secure storage unavailable")
                return buildPlainDatabase(context)
            }
            throw IllegalStateException("Secure storage unavailable: cannot open the encrypted database")
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

    private fun buildEncryptedDatabase(context: Context, passphrase: ByteArray): TeleDriveDatabase {
        return Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            ENCRYPTED_DB_NAME
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            // Current v5 installs upgrade to v6 through MIGRATION_5_6,
            // v6 to v7 through MIGRATION_6_7 and v7 to v8 through
            // MIGRATION_7_8, keeping their data;
            // fallbackToDestructiveMigration stays only as a last resort
            // for pre-v5 installs with no migration path.
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    private fun buildPlainDatabase(context: Context): TeleDriveDatabase {
        return Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            LEGACY_PLAIN_DB_NAME
        )
            // Same as the encrypted builder: v5 -> v6, v6 -> v7 and
            // v7 -> v8 go through the migrations and keep data;
            // destructive fallback is only for pre-v5 installs with no
            // migration path.
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigration(true)
            .build()
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
            val expectedDriveItems = countRows(plainDb, "drive_items")
            val expectedTransfers = countRows(plainDb, "transfers")
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
                val actualDriveItems = countRows(encDb, "drive_items")
                val actualTransfers = countRows(encDb, "transfers")
                if (actualDriveItems != expectedDriveItems || actualTransfers != expectedTransfers) {
                    throw IllegalStateException(
                        "Migration verification failed: drive_items $actualDriveItems != $expectedDriveItems, " +
                            "transfers $actualTransfers != $expectedTransfers"
                    )
                }
            }
            deleteDatabaseFiles(plainFile)
            Log.i(TAG, "Database migration complete: $expectedDriveItems items, $expectedTransfers transfers")
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
            val expectedDriveItems: Long
            val expectedTransfers: Long
            SQLiteDatabase.openDatabase(
                plainFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { plainDb ->
                expectedDriveItems = countRows(plainDb, "drive_items")
                expectedTransfers = countRows(plainDb, "transfers")
            }
            var encryptedComplete = false
            SQLiteDatabase.openDatabase(
                encryptedFile.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null
            ).use { encDb ->
                encryptedComplete =
                    countRows(encDb, "drive_items") == expectedDriveItems &&
                        countRows(encDb, "transfers") == expectedTransfers
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
            Log.e(TAG, "Could not reconcile duplicate databases, keeping both and using the encrypted one", e)
            true
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
