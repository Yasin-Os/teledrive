package com.awd.teledrive.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val TAG = "SecureSettings"

    companion object {
        const val PREFS_NAME = "secure_settings_v2"
        private const val LEGACY_FALLBACK_PREFS_NAME = "fallback_settings"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_b64"
        private const val KEY_TDLIB_ENCRYPTION_KEY = "tdlib_encryption_key_b64"
        private const val KEY_BYTE_LENGTH = 32
    }

    // Fail-closed: if the encrypted store cannot be initialised, secrets are
    // NEVER written to a plaintext fallback (the previous behaviour wrote
    // API hash, PINs and password hashes to cleartext SharedPreferences).
    // Reads then behave as "no value stored" and writes throw, so a broken
    // keystore can never silently downgrade the app to cleartext secrets.
    private var sharedPreferences: SharedPreferences? = null

    /** True only when the encrypted store is usable. */
    val isSecureStorageAvailable: Boolean
        get() = sharedPreferences != null

    init {
        sharedPreferences = try {
            createEncryptedPreferences()
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed, retrying after deleting possibly corrupt prefs", e)
            try {
                // A corrupt prefs file is the most common cause: delete it and retry once.
                context.deleteSharedPreferences(PREFS_NAME)
                createEncryptedPreferences()
            } catch (retryError: Exception) {
                Log.e(TAG, "Secure storage unavailable: secrets will NOT be stored in plaintext", retryError)
                // Remove any plaintext secrets left behind by older builds that
                // used the insecure fallback, so they do not linger on disk.
                try {
                    context.deleteSharedPreferences(LEGACY_FALLBACK_PREFS_NAME)
                } catch (_: Exception) {
                }
                null
            }
        }
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun requirePrefs(): SharedPreferences {
        return sharedPreferences ?: throw IllegalStateException(
            "Secure storage unavailable: encrypted preferences failed to initialize; refusing to store secrets in plaintext"
        )
    }

    fun saveString(key: String, value: String) {
        requirePrefs().edit().putString(key, value).apply()
    }

    fun removeString(key: String) {
        requirePrefs().edit().remove(key).apply()
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return sharedPreferences?.getString(key, defaultValue) ?: defaultValue
    }

    fun saveBoolean(key: String, value: Boolean) {
        requirePrefs().edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences?.getBoolean(key, defaultValue) ?: defaultValue
    }

    fun saveInt(key: String, value: Int) {
        requirePrefs().edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences?.getInt(key, defaultValue) ?: defaultValue
    }

    fun saveLong(key: String, value: Long) {
        requirePrefs().edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences?.getLong(key, defaultValue) ?: defaultValue
    }

    fun saveStringList(key: String, list: List<String>) {
        val data = list.joinToString(";")
        saveString(key, data)
    }

    fun getStringList(key: String): List<String> {
        val data = getString(key, "") ?: ""
        return if (data.isEmpty()) emptyList() else data.split(";")
    }

    /**
     * Random 32-byte passphrase for the SQLCipher-encrypted Room database,
     * generated once and kept in the encrypted store. The same bytes are used
     * both for the Room SupportOpenHelperFactory and for the one-time
     * plaintext-to-encrypted migration, so the key derivation always matches.
     */
    fun getOrCreateDatabasePassphrase(): ByteArray {
        return getOrCreateKey(KEY_DB_PASSPHRASE)
    }

    /**
     * Random 32-byte encryption key for the TDLib database, generated once and
     * kept in the encrypted store. Only used for fresh TDLib databases (see
     * AuthRepository); existing unencrypted TDLib databases keep no key.
     */
    fun getOrCreateTdlibEncryptionKey(): ByteArray {
        return getOrCreateKey(KEY_TDLIB_ENCRYPTION_KEY)
    }

    /** Stored TDLib key, or null if none was ever created. Does not create one. */
    fun getStoredTdlibEncryptionKey(): ByteArray? {
        val stored = sharedPreferences?.getString(KEY_TDLIB_ENCRYPTION_KEY, null)
        if (stored.isNullOrEmpty()) return null
        return try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Stored TDLib encryption key is corrupt", e)
            null
        }
    }

    private fun getOrCreateKey(prefsKey: String): ByteArray {
        val prefs = requirePrefs()
        val existing = prefs.getString(prefsKey, null)
        if (!existing.isNullOrEmpty()) {
            try {
                return Base64.decode(existing, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Stored key $prefsKey is corrupt, generating a fresh one", e)
            }
        }
        val key = ByteArray(KEY_BYTE_LENGTH).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(prefsKey, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        return key
    }

    fun isApiConfigured(): Boolean {
        return getInt("api_id", 0) != 0 && !getString("api_hash", "").isNullOrEmpty()
    }

    fun isSecurityEnabled(): Boolean {
        return getBoolean("security_enabled", false)
    }

    fun setSecurityEnabled(enabled: Boolean) {
        saveBoolean("security_enabled", enabled)
    }

    fun saveDownloadUri(uri: String) {
        saveString("download_uri", uri)
    }

    fun getDownloadUri(): String? {
        return getString("download_uri")
    }
}
