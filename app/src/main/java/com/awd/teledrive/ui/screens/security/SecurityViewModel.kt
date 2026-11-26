package com.awd.teledrive.ui.screens.security

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.core.BiometricCipherResult
import com.awd.teledrive.core.BiometricHelper
import com.awd.teledrive.data.repository.DriveRepository
import com.awd.teledrive.data.secure.EncryptionManager
import com.awd.teledrive.data.secure.MasterPasswordService
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val secureSessionManager: SecureSessionManager,
    private val encryptionManager: EncryptionManager,
    private val masterPasswordService: MasterPasswordService,
    private val driveRepository: DriveRepository,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "SecurityViewModel"
        private const val STORAGE_UNAVAILABLE_ERROR =
            "Secure storage is unavailable. Please unlock your device and try again."

        // Legacy plaintext PIN keys (migrated and deleted on first verify/change).
        private const val KEY_LEGACY_APP_LOCK_PIN = "app_lock_pin_code_v2"
        private const val KEY_LEGACY_SAFE_FOLDER_PIN = "safe_folder_pin_code_v2"

        // Hardened App Lock keys (salted hash + Keystore CryptoObject biometric token).
        private const val KEY_APP_LOCK_PIN_HASH = "app_lock_pin_hash_v3"
        private const val KEY_APP_LOCK_ENABLED = "security_enabled"
        private const val KEY_APP_BIOMETRIC = "biometric_enabled"
        private const val KEY_APP_BIOMETRIC_WRAPPED_TOKEN = "app_lock_biometric_wrapped_token_v2"
        private const val KEY_APP_BIOMETRIC_TOKEN_DIGEST = "app_lock_biometric_token_digest_v2"
        private const val APP_LOCK_BIOMETRIC_KEY_ALIAS = "mycloud_app_lock_biometric_v2"
        private val APP_LOCK_BIOMETRIC_AAD = "MyCloud-AppLock-BIO-v2".toByteArray(Charsets.UTF_8)

        // Hardened Safe Folder Vault keys (random per-vault DEK wrapped by PIN KEK and Keystore).
        private const val KEY_VAULT_WRAPPED_DEK_PIN = "safe_folder_wrapped_dek_pin_v2"
        private const val KEY_VAULT_WRAPPED_DEK_BIOMETRIC = "safe_folder_wrapped_dek_bio_v2"
        private const val KEY_VAULT_LEGACY_MIGRATION_PENDING = "safe_folder_legacy_migration_pending_v2"
        private const val VAULT_BIOMETRIC_KEY_ALIAS = "mycloud_vault_biometric_kek_v2"

        private const val KEY_SAFE_FOLDER_LOCK_ENABLED = "safe_folder_lock_enabled_v2"
        private const val KEY_SAFE_FOLDER_BIOMETRIC = "safe_folder_biometric_enabled"
        private const val KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES = "safe_folder_auto_lock_minutes"
        private const val KEY_VAULT_BACKGROUNDED_AT = "safe_folder_backgrounded_at_ms"

        // L6: lockout deadlines and the backgrounded timestamp run on
        // SystemClock.elapsedRealtime() (monotonic). Values above this
        // threshold are legacy wall-clock epoch millis and are migrated once.
        private const val LEGACY_WALL_CLOCK_THRESHOLD_MS = 1_000_000_000_000L

        /** Auto-lock choice meaning "never lock on background". */
        const val AUTO_LOCK_NEVER = -1
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        private val AUTO_LOCK_OPTIONS = setOf(1, 5, 15, 30, AUTO_LOCK_NEVER)

        // PIN brute-force protection: 5 wrong attempts lock the PIN for
        // 30 seconds, doubling on every further lockout (max 5 minutes).
        private const val MAX_PIN_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 5 * 60_000L

        private val APP_PIN_LOCKOUT_KEYS = PinLockoutKeys(
            attempts = "app_pin_failed_attempts",
            lockoutUntil = "app_pin_lockout_until_ms",
            lockoutCount = "app_pin_lockout_count"
        )
        private val SAFE_PIN_LOCKOUT_KEYS = PinLockoutKeys(
            attempts = "safe_pin_failed_attempts",
            lockoutUntil = "safe_pin_lockout_until_ms",
            lockoutCount = "safe_pin_lockout_count"
        )
    }

    private data class PinLockoutKeys(
        val attempts: String,
        val lockoutUntil: String,
        val lockoutCount: String
    )

    private data class MigrationOutcome(
        val migratedCount: Int,
        val ioFailureCount: Int
    )

    private val secureRandom = SecureRandom()

    private val argon2Deriver: (CharArray, ByteArray) -> ByteArray? = { pinChars, salt ->
        masterPasswordService.deriveKeyArgon2id(pinChars, salt)
    }

    // Fail-closed strict read helpers (H22): when SecureSettings fails to
    // initialize, every lock read throws IllegalStateException rather than
    // silently returning false/null ("no PIN" / "unlocked"). We catch the
    // exception and return the CLOSED default (locked, PIN assumed set).
    private fun readAppPinSetFailClosed(): Boolean {
        return try {
            !secureSettings.getStringStrict(KEY_APP_LOCK_PIN_HASH).isNullOrEmpty() ||
                !secureSettings.getStringStrict(KEY_LEGACY_APP_LOCK_PIN).isNullOrEmpty()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading App Lock PIN state; failing closed", e)
            true
        }
    }

    private fun readAppLockEnabledFailClosed(): Boolean {
        return try {
            secureSettings.getBooleanStrict(KEY_APP_LOCK_ENABLED, false) && readAppPinSetFailClosed()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading App Lock enabled state; failing closed", e)
            true
        }
    }

    private fun readAppBiometricEnabledFailClosed(): Boolean {
        return try {
            secureSettings.getBooleanStrict(KEY_APP_BIOMETRIC, false) &&
                !secureSettings.getStringStrict(KEY_APP_BIOMETRIC_WRAPPED_TOKEN).isNullOrEmpty() &&
                !secureSettings.getStringStrict(KEY_APP_BIOMETRIC_TOKEN_DIGEST).isNullOrEmpty() &&
                BiometricHelper.hasBiometricKey(APP_LOCK_BIOMETRIC_KEY_ALIAS)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading App Lock biometric state", e)
            false
        }
    }

    private fun readSafeFolderPinSetFailClosed(): Boolean {
        return try {
            !secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_PIN).isNullOrEmpty() ||
                !secureSettings.getStringStrict(KEY_LEGACY_SAFE_FOLDER_PIN).isNullOrEmpty()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading Safe Folder PIN state; failing closed", e)
            true
        }
    }

    private fun readSafeFolderLockEnabledFailClosed(): Boolean {
        return try {
            secureSettings.getBooleanStrict(KEY_SAFE_FOLDER_LOCK_ENABLED, true)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading Safe Folder lock state; failing closed", e)
            true
        }
    }

    private fun readSafeFolderBiometricEnabledFailClosed(): Boolean {
        return try {
            secureSettings.getBooleanStrict(KEY_SAFE_FOLDER_BIOMETRIC, false) &&
                !secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_BIOMETRIC).isNullOrEmpty() &&
                BiometricHelper.hasBiometricKey(VAULT_BIOMETRIC_KEY_ALIAS)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading Safe Folder biometric state", e)
            false
        }
    }

    private fun readAutoLockMinutesFailClosed(): Int {
        return try {
            secureSettings.getIntStrict(KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES, DEFAULT_AUTO_LOCK_MINUTES)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading auto-lock timeout; using strict default", e)
            1
        }
    }

    // 1. Whole App Lock state (fail-closed)
    private val _isPasswordSet = MutableStateFlow(readAppPinSetFailClosed())
    val isPasswordSet: StateFlow<Boolean> = _isPasswordSet.asStateFlow()

    private val _isSecurityEnabled = MutableStateFlow(readAppLockEnabledFailClosed())
    val isSecurityEnabled: StateFlow<Boolean> = _isSecurityEnabled.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(readAppBiometricEnabledFailClosed())
    val isBiometricEnabled: StateFlow<Boolean> = _isBiometricEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(readAppLockEnabledFailClosed())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // PIN lockout countdowns (seconds left, 0 = not locked), for the UI.
    private val _appPinLockoutRemaining = MutableStateFlow(0L)
    val appPinLockoutRemainingSeconds: StateFlow<Long> = _appPinLockoutRemaining.asStateFlow()

    private val _safePinLockoutRemaining = MutableStateFlow(0L)
    val safePinLockoutRemainingSeconds: StateFlow<Long> = _safePinLockoutRemaining.asStateFlow()

    private val _safePinError = MutableStateFlow<String?>(null)
    val safePinError: StateFlow<String?> = _safePinError.asStateFlow()

    private var appPinTicker: Job? = null
    private var safePinTicker: Job? = null

    // 2. Safe Folder independent PIN & vault crypto state (fail-closed)
    private val _isSafeFolderPinSet = MutableStateFlow(readSafeFolderPinSetFailClosed())
    val isSafeFolderPinSet: StateFlow<Boolean> = _isSafeFolderPinSet.asStateFlow()

    private val _isSafeFolderLockEnabled = MutableStateFlow(readSafeFolderLockEnabledFailClosed())
    val isSafeFolderLockEnabled: StateFlow<Boolean> = _isSafeFolderLockEnabled.asStateFlow()

    val isSecureModeActive: StateFlow<Boolean> = secureSessionManager.isSessionUnlocked

    private val _isSafeFolderBiometricEnabled = MutableStateFlow(readSafeFolderBiometricEnabledFailClosed())
    val isSafeFolderBiometricEnabled: StateFlow<Boolean> = _isSafeFolderBiometricEnabled.asStateFlow()

    private val _safeFolderAutoLockMinutes = MutableStateFlow(readAutoLockMinutesFailClosed())
    val safeFolderAutoLockMinutes: StateFlow<Int> = _safeFolderAutoLockMinutes.asStateFlow()

    init {
        // Resume countdowns if the app was restarted mid-lockout (strict reads).
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
        }
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
        }
    }

    /**
     * L6 + H22: reads a stored lockout deadline on the monotonic clock via
     * [SecureSettings.getLongStrict]. Fails closed (Long.MAX_VALUE) if secure
     * storage is unavailable.
     */
    private fun readLockoutDeadline(keys: PinLockoutKeys): Long {
        val stored = try {
            secureSettings.getLongStrict(keys.lockoutUntil, 0L)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading lockout deadline; failing closed", e)
            return Long.MAX_VALUE
        }
        if (stored == 0L || stored < LEGACY_WALL_CLOCK_THRESHOLD_MS) return stored
        val remainingMs = stored - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            try {
                secureSettings.saveLong(keys.lockoutUntil, 0L)
            } catch (_: IllegalStateException) {
            }
            return 0L
        }
        val migrated = SystemClock.elapsedRealtime() + remainingMs
        try {
            secureSettings.saveLong(keys.lockoutUntil, migrated)
        } catch (_: IllegalStateException) {
        }
        return migrated
    }

    private fun isPinLocked(keys: PinLockoutKeys): Boolean {
        return readLockoutDeadline(keys) > SystemClock.elapsedRealtime()
    }

    private fun pinLockoutRemainingSeconds(keys: PinLockoutKeys): Long {
        val deadline = readLockoutDeadline(keys)
        if (deadline == Long.MAX_VALUE) return MAX_LOCKOUT_MS / 1000L
        return ((deadline - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
    }

    private fun lockoutMessage(keys: PinLockoutKeys): String {
        if (!secureSettings.isSecureStorageAvailable) return STORAGE_UNAVAILABLE_ERROR
        return "Too many wrong attempts. Try again in ${pinLockoutRemainingSeconds(keys)}s"
    }

    private fun recordFailedPinAttempt(keys: PinLockoutKeys, flow: MutableStateFlow<Long>, startTicker: () -> Unit) {
        try {
            val attempts = secureSettings.getIntStrict(keys.attempts, 0) + 1
            if (attempts >= MAX_PIN_ATTEMPTS) {
                val count = secureSettings.getIntStrict(keys.lockoutCount, 0)
                val duration = minOf(BASE_LOCKOUT_MS * (1L shl minOf(count, 4)), MAX_LOCKOUT_MS)
                secureSettings.editSync {
                    putLong(keys.lockoutUntil, SystemClock.elapsedRealtime() + duration)
                    putInt(keys.lockoutCount, count + 1)
                    putInt(keys.attempts, 0)
                }
                startTicker()
                flow.value = pinLockoutRemainingSeconds(keys)
            } else {
                secureSettings.saveInt(keys.attempts, attempts)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to persist PIN attempt counter; secure storage unavailable", e)
        }
    }

    private fun resetPinAttempts(keys: PinLockoutKeys) {
        try {
            secureSettings.editSync {
                putInt(keys.attempts, 0)
                putInt(keys.lockoutCount, 0)
                putLong(keys.lockoutUntil, 0L)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to reset PIN attempt counters", e)
        }
    }

    private fun startLockoutTicker(keys: PinLockoutKeys, flow: MutableStateFlow<Long>): Job {
        return viewModelScope.launch {
            while (true) {
                val remaining = pinLockoutRemainingSeconds(keys)
                flow.value = remaining
                if (remaining <= 0L) break
                delay(1000L)
            }
        }
    }

    // --- Whole App Lock Functions ---

    fun setSecurityEnabled(enabled: Boolean) {
        try {
            secureSettings.saveBoolean(KEY_APP_LOCK_ENABLED, enabled)
            _isSecurityEnabled.value = enabled
            if (!enabled) {
                _isLocked.value = false
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot toggle app lock: secure storage unavailable", e)
            _error.value = STORAGE_UNAVAILABLE_ERROR
        }
    }

    fun setLocked(locked: Boolean) {
        if (locked && (_isSecurityEnabled.value && _isPasswordSet.value)) {
            _isLocked.value = true
        } else if (!locked && !_isSecurityEnabled.value) {
            _isLocked.value = false
        }
    }

    suspend fun setPassword(pin: String): Boolean {
        return withContext(Dispatchers.Default) {
            val pinChars = pin.toCharArray()
            try {
                val hashRecord = masterPasswordService.hashSecret(pinChars)
                secureSettings.editSync {
                    putString(KEY_APP_LOCK_PIN_HASH, hashRecord)
                    putBoolean(KEY_APP_LOCK_ENABLED, true)
                    remove(KEY_LEGACY_APP_LOCK_PIN)
                }
                _isPasswordSet.value = true
                _isSecurityEnabled.value = true
                _isLocked.value = false
                _error.value = null
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set App Lock PIN", e)
                _error.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                pinChars.fill('\u0000')
            }
        }
    }

    suspend fun changeAppLockPin(oldPin: String, newPin: String): Boolean {
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            _error.value = lockoutMessage(APP_PIN_LOCKOUT_KEYS)
            return false
        }
        return withContext(Dispatchers.Default) {
            val storedHash: String?
            val legacyPin: String?
            try {
                storedHash = secureSettings.getStringStrict(KEY_APP_LOCK_PIN_HASH)
                legacyPin = secureSettings.getStringStrict(KEY_LEGACY_APP_LOCK_PIN)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot change App Lock PIN: secure storage unavailable", e)
                _error.value = STORAGE_UNAVAILABLE_ERROR
                return@withContext false
            }

            // Fail closed: never allow changing PIN when no existing PIN is stored.
            if (storedHash.isNullOrEmpty() && legacyPin.isNullOrEmpty()) {
                _error.value = "No App Lock PIN is currently set"
                return@withContext false
            }

            val oldChars = oldPin.toCharArray()
            val newChars = newPin.toCharArray()
            try {
                val verified = if (!storedHash.isNullOrEmpty()) {
                    masterPasswordService.verifySecret(oldChars, storedHash)
                } else {
                    constantTimeEquals(legacyPin!!, oldPin)
                }

                if (!verified) {
                    recordFailedPinAttempt(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining) {
                        appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
                    }
                    _error.value = if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) lockoutMessage(APP_PIN_LOCKOUT_KEYS) else "Incorrect PIN"
                    return@withContext false
                }

                val newHashRecord = masterPasswordService.hashSecret(newChars)
                secureSettings.editSync {
                    putString(KEY_APP_LOCK_PIN_HASH, newHashRecord)
                    remove(KEY_LEGACY_APP_LOCK_PIN)
                }
                resetPinAttempts(APP_PIN_LOCKOUT_KEYS)
                _isPasswordSet.value = true
                _error.value = null
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change App Lock PIN", e)
                _error.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                oldChars.fill('\u0000')
                newChars.fill('\u0000')
            }
        }
    }

    suspend fun verifyPassword(pin: String): Boolean {
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            _error.value = lockoutMessage(APP_PIN_LOCKOUT_KEYS)
            if (appPinTicker?.isActive != true) {
                appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
            }
            return false
        }
        return withContext(Dispatchers.Default) {
            val storedHash: String?
            val legacyPin: String?
            try {
                storedHash = secureSettings.getStringStrict(KEY_APP_LOCK_PIN_HASH)
                legacyPin = secureSettings.getStringStrict(KEY_LEGACY_APP_LOCK_PIN)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot verify App Lock PIN: secure storage unavailable", e)
                _error.value = STORAGE_UNAVAILABLE_ERROR
                return@withContext false
            }

            val pinChars = pin.toCharArray()
            try {
                val isValid = when {
                    !storedHash.isNullOrEmpty() -> masterPasswordService.verifySecret(pinChars, storedHash)
                    !legacyPin.isNullOrEmpty() -> {
                        val matched = constantTimeEquals(legacyPin, pin)
                        if (matched) {
                            // One-time migration: replace legacy plaintext App Lock PIN with salted hash.
                            val migratedHash = masterPasswordService.hashSecret(pinChars)
                            secureSettings.editSync {
                                putString(KEY_APP_LOCK_PIN_HASH, migratedHash)
                                remove(KEY_LEGACY_APP_LOCK_PIN)
                            }
                        }
                        matched
                    }
                    else -> false
                }

                if (!isValid) {
                    recordFailedPinAttempt(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining) {
                        appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
                    }
                    _error.value = if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) lockoutMessage(APP_PIN_LOCKOUT_KEYS) else "Incorrect PIN"
                } else {
                    resetPinAttempts(APP_PIN_LOCKOUT_KEYS)
                    _error.value = null
                    _isLocked.value = false
                }
                isValid
            } catch (e: Exception) {
                Log.e(TAG, "App Lock PIN verification error", e)
                _error.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                pinChars.fill('\u0000')
            }
        }
    }

    // --- Whole App Lock Biometric (Keystore CryptoObject) ---

    fun prepareAppLockBiometricEnrollCipher(): Cipher? {
        if (!BiometricHelper.isBiometricAvailable(appContext)) return null
        if (!secureSettings.isSecureStorageAvailable) return null
        return BiometricHelper.createEncryptCipher(APP_LOCK_BIOMETRIC_KEY_ALIAS)
    }

    fun enableAppLockBiometricWithCipher(authenticatedCipher: Cipher): Boolean {
        val token = ByteArray(EncryptionManager.DEK_LENGTH_BYTE).also { secureRandom.nextBytes(it) }
        return try {
            val digest = sha256Hex(token)
            val wrapped = encryptionManager.wrapSecretWithCipher(token, APP_LOCK_BIOMETRIC_AAD, authenticatedCipher)
            secureSettings.editSync {
                putString(KEY_APP_BIOMETRIC_WRAPPED_TOKEN, wrapped)
                putString(KEY_APP_BIOMETRIC_TOKEN_DIGEST, digest)
                putBoolean(KEY_APP_BIOMETRIC, true)
            }
            _isBiometricEnabled.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enroll App Lock biometric credential", e)
            BiometricHelper.deleteBiometricKey(APP_LOCK_BIOMETRIC_KEY_ALIAS)
            false
        } finally {
            token.fill(0)
        }
    }

    fun disableAppLockBiometric() {
        BiometricHelper.deleteBiometricKey(APP_LOCK_BIOMETRIC_KEY_ALIAS)
        try {
            secureSettings.editSync {
                putBoolean(KEY_APP_BIOMETRIC, false)
                remove(KEY_APP_BIOMETRIC_WRAPPED_TOKEN)
                remove(KEY_APP_BIOMETRIC_TOKEN_DIGEST)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to persist disabled App Lock biometric state", e)
        }
        _isBiometricEnabled.value = false
    }

    fun prepareAppLockBiometricUnlockCipher(): BiometricCipherResult {
        if (!BiometricHelper.isBiometricAvailable(appContext)) {
            return BiometricCipherResult.Unavailable("Biometric unlock is not available on this device.")
        }
        val wrapped = try {
            if (!secureSettings.getBooleanStrict(KEY_APP_BIOMETRIC, false)) {
                return BiometricCipherResult.Unavailable("Biometric unlock is disabled.")
            }
            secureSettings.getStringStrict(KEY_APP_BIOMETRIC_WRAPPED_TOKEN)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable preparing App Lock biometric cipher", e)
            return BiometricCipherResult.Unavailable(STORAGE_UNAVAILABLE_ERROR)
        }
        val iv = wrapped?.let { encryptionManager.extractBiometricWrapIv(it) }
            ?: return BiometricCipherResult.Unavailable("Biometric unlock must be re-enabled in Security settings.")

        val result = BiometricHelper.createDecryptCipher(APP_LOCK_BIOMETRIC_KEY_ALIAS, iv)
        if (result is BiometricCipherResult.KeyInvalidated) {
            disableAppLockBiometric()
        }
        return result
    }

    /**
     * Unlocks the app ONLY if [authenticatedCipher] (returned by BiometricPrompt's
     * CryptoObject) successfully decrypts the Keystore-wrapped token and its
     * SHA-256 digest matches in constant time.
     */
    fun unlockAppWithBiometric(authenticatedCipher: Cipher): Boolean {
        val wrapped: String
        val expectedDigest: String
        try {
            if (!secureSettings.getBooleanStrict(KEY_APP_BIOMETRIC, false)) return false
            wrapped = secureSettings.getStringStrict(KEY_APP_BIOMETRIC_WRAPPED_TOKEN) ?: return false
            expectedDigest = secureSettings.getStringStrict(KEY_APP_BIOMETRIC_TOKEN_DIGEST) ?: return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable unlocking app with biometric; failing closed", e)
            _error.value = STORAGE_UNAVAILABLE_ERROR
            return false
        }

        val token = encryptionManager.unwrapSecretWithCipher(wrapped, APP_LOCK_BIOMETRIC_AAD, authenticatedCipher)
            ?: return false
        return try {
            val actualDigest = sha256Hex(token)
            if (constantTimeEquals(actualDigest, expectedDigest)) {
                resetPinAttempts(APP_PIN_LOCKOUT_KEYS)
                _error.value = null
                _isLocked.value = false
                true
            } else {
                false
            }
        } finally {
            token.fill(0)
        }
    }

    // --- Safe Folder Vault Crypto Functions (Per-Vault Random DEK + PIN KEK Wrapping) ---

    suspend fun setupSafeFolderPin(pin: String): Boolean {
        return withContext(Dispatchers.Default) {
            // Fail closed: never silently overwrite an existing vault DEK.
            val alreadyConfigured = try {
                !secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_PIN).isNullOrEmpty() ||
                    !secureSettings.getStringStrict(KEY_LEGACY_SAFE_FOLDER_PIN).isNullOrEmpty()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot setup Safe Folder PIN: secure storage unavailable", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                return@withContext false
            }
            if (alreadyConfigured) {
                _safePinError.value = "Safe Folder PIN is already configured"
                return@withContext false
            }

            val pinChars = pin.toCharArray()
            val dek = encryptionManager.generateDek()
            try {
                val wrappedDek = encryptionManager.wrapDekWithPin(dek, pinChars, argon2Deriver)
                secureSettings.editSync {
                    putString(KEY_VAULT_WRAPPED_DEK_PIN, wrappedDek)
                    putBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, true)
                    putBoolean(KEY_VAULT_LEGACY_MIGRATION_PENDING, false)
                    remove(KEY_LEGACY_SAFE_FOLDER_PIN)
                }
                secureSessionManager.setSessionKey(dek)
                _isSafeFolderPinSet.value = true
                _isSafeFolderLockEnabled.value = true
                _safePinError.value = null
                clearVaultBackgroundedAt()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up Safe Folder vault DEK", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                pinChars.fill('\u0000')
                dek.fill(0)
            }
        }
    }

    suspend fun verifySafeFolderPin(pin: String): Boolean {
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            _safePinError.value = lockoutMessage(SAFE_PIN_LOCKOUT_KEYS)
            if (safePinTicker?.isActive != true) {
                safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
            }
            return false
        }

        return withContext(Dispatchers.Default) {
            val wrappedDekPin: String?
            val legacyPin: String?
            val migrationPending: Boolean
            try {
                wrappedDekPin = secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_PIN)
                legacyPin = secureSettings.getStringStrict(KEY_LEGACY_SAFE_FOLDER_PIN)
                migrationPending = secureSettings.getBooleanStrict(KEY_VAULT_LEGACY_MIGRATION_PENDING, false)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot verify Safe Folder PIN: secure storage unavailable", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                return@withContext false
            }

            val pinChars = pin.toCharArray()
            var activeDek: ByteArray? = null
            try {
                if (!wrappedDekPin.isNullOrEmpty()) {
                    val unwrapped = encryptionManager.unwrapDekWithPin(wrappedDekPin, pinChars, argon2Deriver)
                    if (unwrapped == null) {
                        recordSafeFolderFailure()
                        return@withContext false
                    }
                    activeDek = unwrapped
                    secureSessionManager.setSessionKey(unwrapped)
                    if (migrationPending) {
                        val outcome = migrateLegacyEncryptedFiles(pinChars, unwrapped)
                        if (outcome.ioFailureCount == 0) {
                            secureSettings.saveBoolean(KEY_VAULT_LEGACY_MIGRATION_PENDING, false)
                        }
                    }
                    // Ensure no stale plaintext legacy PIN lingers on disk.
                    if (!legacyPin.isNullOrEmpty()) {
                        try {
                            secureSettings.removeString(KEY_LEGACY_SAFE_FOLDER_PIN)
                        } catch (_: IllegalStateException) {
                        }
                    }
                } else if (!legacyPin.isNullOrEmpty()) {
                    // Upgrade from pre-DEK vault: authenticate legacy plaintext PIN in
                    // constant time, generate per-vault random DEK, wrap under PIN KEK,
                    // delete plaintext PIN, and migrate legacy local .enc files to DEK.
                    if (!constantTimeEquals(legacyPin, pin)) {
                        recordSafeFolderFailure()
                        return@withContext false
                    }
                    val freshDek = encryptionManager.generateDek()
                    activeDek = freshDek
                    val newWrappedDek = encryptionManager.wrapDekWithPin(freshDek, pinChars, argon2Deriver)
                    secureSettings.editSync {
                        putString(KEY_VAULT_WRAPPED_DEK_PIN, newWrappedDek)
                        putBoolean(KEY_VAULT_LEGACY_MIGRATION_PENDING, true)
                        remove(KEY_LEGACY_SAFE_FOLDER_PIN)
                    }
                    secureSessionManager.setSessionKey(freshDek)
                    val outcome = migrateLegacyEncryptedFiles(pinChars, freshDek)
                    if (outcome.ioFailureCount == 0) {
                        secureSettings.saveBoolean(KEY_VAULT_LEGACY_MIGRATION_PENDING, false)
                    }
                } else {
                    recordSafeFolderFailure()
                    return@withContext false
                }

                resetPinAttempts(SAFE_PIN_LOCKOUT_KEYS)
                _safePinError.value = null
                _isSafeFolderPinSet.value = true
                clearVaultBackgroundedAt()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Safe Folder PIN verification failed", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                pinChars.fill('\u0000')
                activeDek?.fill(0)
            }
        }
    }

    /**
     * Changes the Safe Folder PIN WITHOUT orphaning encrypted files:
     * 1. Authenticates [oldPin] and unwraps the existing vault DEK (or upgrades
     *    a legacy plaintext PIN to a fresh DEK).
     * 2. Migrates any remaining legacy PIN-encrypted local files to v2 DEK
     *    format (decrypt with [oldPin], re-encrypt under DEK). Files already in
     *    v2 format are skipped in O(1). If an I/O error prevents migrating a
     *    legacy file, aborts before touching the stored wrapped DEK.
     * 3. Re-wraps ONLY the DEK under [newPin]'s KEK and persists atomically.
     *    Any biometric-wrapped copy of the DEK stays valid because the DEK
     *    itself is unchanged.
     */
    suspend fun changeSafeFolderPin(oldPin: String, newPin: String): Boolean {
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            _safePinError.value = lockoutMessage(SAFE_PIN_LOCKOUT_KEYS)
            return false
        }

        return withContext(Dispatchers.Default) {
            val wrappedDekPin: String?
            val legacyPin: String?
            try {
                wrappedDekPin = secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_PIN)
                legacyPin = secureSettings.getStringStrict(KEY_LEGACY_SAFE_FOLDER_PIN)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot change Safe Folder PIN: secure storage unavailable", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                return@withContext false
            }

            // Fail closed: never allow PIN change when no credential exists.
            if (wrappedDekPin.isNullOrEmpty() && legacyPin.isNullOrEmpty()) {
                _safePinError.value = "Safe Folder PIN is not set"
                return@withContext false
            }

            val oldChars = oldPin.toCharArray()
            val newChars = newPin.toCharArray()
            var dek: ByteArray? = null
            try {
                dek = when {
                    !wrappedDekPin.isNullOrEmpty() -> {
                        encryptionManager.unwrapDekWithPin(wrappedDekPin, oldChars, argon2Deriver)
                    }
                    else -> {
                        if (constantTimeEquals(legacyPin!!, oldPin)) {
                            encryptionManager.generateDek()
                        } else {
                            null
                        }
                    }
                }

                if (dek == null) {
                    recordSafeFolderFailure()
                    return@withContext false
                }

                // Migrate any remaining legacy PIN-encrypted local files BEFORE
                // re-wrapping the DEK under newPin so oldPin is never lost while
                // decryptable legacy files remain on disk.
                val migration = migrateLegacyEncryptedFiles(oldChars, dek)
                if (migration.ioFailureCount > 0) {
                    _safePinError.value =
                        "Could not migrate ${migration.ioFailureCount} encrypted file(s). Check storage space and try again."
                    return@withContext false
                }

                val rewrappedDekPin = encryptionManager.wrapDekWithPin(dek, newChars, argon2Deriver)
                secureSettings.editSync {
                    putString(KEY_VAULT_WRAPPED_DEK_PIN, rewrappedDekPin)
                    putBoolean(KEY_VAULT_LEGACY_MIGRATION_PENDING, false)
                    remove(KEY_LEGACY_SAFE_FOLDER_PIN)
                }

                resetPinAttempts(SAFE_PIN_LOCKOUT_KEYS)
                secureSessionManager.setSessionKey(dek)
                _isSafeFolderPinSet.value = true
                _safePinError.value = null
                clearVaultBackgroundedAt()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change Safe Folder PIN", e)
                _safePinError.value = STORAGE_UNAVAILABLE_ERROR
                false
            } finally {
                oldChars.fill('\u0000')
                newChars.fill('\u0000')
                dek?.fill(0)
            }
        }
    }

    /**
     * Scans all on-device encrypted files recorded in the database and migrates
     * any legacy v1 PIN-encrypted file to the v2 DEK format using [legacyPin]
     * and [dek]. Files already in v2 format are skipped immediately.
     */
    private suspend fun migrateLegacyEncryptedFiles(
        legacyPin: CharArray,
        dek: ByteArray
    ): MigrationOutcome = withContext(Dispatchers.IO) {
        var migrated = 0
        var ioFailures = 0
        val paths = try {
            driveRepository.getEncryptedLocalFilePaths()
        } catch (e: Exception) {
            Log.e(TAG, "Could not enumerate encrypted local files for migration", e)
            return@withContext MigrationOutcome(migratedCount = 0, ioFailureCount = 1)
        }

        for (path in paths) {
            val file = File(path)
            if (!file.exists() || !file.isFile) continue
            if (encryptionManager.isV2EncryptedFile(file)) continue
            try {
                if (encryptionManager.migrateLegacyFileToDek(file, legacyPin, dek)) {
                    migrated++
                }
            } catch (e: BadPaddingException) {
                // File was not encrypted with this PIN (e.g. corrupt or already
                // orphaned by a pre-fix build); log and continue so the user is
                // not permanently blocked from changing their PIN.
                Log.w(TAG, "Skipping undecryptable legacy file during migration: ${file.name}", e)
            } catch (e: IOException) {
                Log.e(TAG, "I/O failure migrating legacy encrypted file: ${file.name}", e)
                ioFailures++
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error migrating legacy encrypted file: ${file.name}", e)
                ioFailures++
            }
        }
        MigrationOutcome(migratedCount = migrated, ioFailureCount = ioFailures)
    }

    private fun recordSafeFolderFailure() {
        recordFailedPinAttempt(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining) {
            safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
        }
        _safePinError.value = if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            lockoutMessage(SAFE_PIN_LOCKOUT_KEYS)
        } else {
            null
        }
    }

    fun setSafeFolderLockEnabled(enabled: Boolean) {
        try {
            secureSettings.saveBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, enabled)
            _isSafeFolderLockEnabled.value = enabled
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot update Safe Folder lock toggle: secure storage unavailable", e)
            _safePinError.value = STORAGE_UNAVAILABLE_ERROR
        }
    }

    suspend fun unlockSecureMode(password: String): Boolean = verifySafeFolderPin(password)

    fun lockSecureMode() {
        secureSessionManager.clearSession()
    }

    // --- Vault Biometric Unlock (Android Keystore CryptoObject) ---

    fun canUnlockSafeFolderWithBiometric(): Boolean {
        return _isSafeFolderBiometricEnabled.value &&
            _isSafeFolderPinSet.value &&
            BiometricHelper.isBiometricAvailable(appContext)
    }

    /**
     * Prepares a hardware-backed Keystore ENCRYPT Cipher to wrap the active
     * vault DEK after biometric confirmation. Requires the vault to be unlocked
     * (so the DEK is available in [SecureSessionManager]).
     */
    fun prepareSafeFolderBiometricEnrollCipher(): Cipher? {
        if (!secureSessionManager.isUnlocked()) return null
        if (!BiometricHelper.isBiometricAvailable(appContext)) return null
        if (!secureSettings.isSecureStorageAvailable) return null
        return BiometricHelper.createEncryptCipher(VAULT_BIOMETRIC_KEY_ALIAS)
    }

    /**
     * Completes Safe Folder biometric enrollment using the Keystore-authenticated
     * [authenticatedCipher] returned by BiometricPrompt's CryptoObject: wraps the
     * active 256-bit DEK (never the PIN) and persists it atomically.
     */
    fun enableSafeFolderBiometricWithCipher(authenticatedCipher: Cipher): Boolean {
        val dek = secureSessionManager.getSessionKeyCopy() ?: return false
        return try {
            val wrappedDek = encryptionManager.wrapDekWithCipher(dek, authenticatedCipher)
            secureSettings.editSync {
                putString(KEY_VAULT_WRAPPED_DEK_BIOMETRIC, wrappedDek)
                putBoolean(KEY_SAFE_FOLDER_BIOMETRIC, true)
            }
            _isSafeFolderBiometricEnabled.value = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wrap vault DEK with biometric Keystore cipher", e)
            BiometricHelper.deleteBiometricKey(VAULT_BIOMETRIC_KEY_ALIAS)
            false
        } finally {
            dek.fill(0)
        }
    }

    fun disableSafeFolderBiometric() {
        BiometricHelper.deleteBiometricKey(VAULT_BIOMETRIC_KEY_ALIAS)
        try {
            secureSettings.editSync {
                putBoolean(KEY_SAFE_FOLDER_BIOMETRIC, false)
                remove(KEY_VAULT_WRAPPED_DEK_BIOMETRIC)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to persist disabled Safe Folder biometric state", e)
        }
        _isSafeFolderBiometricEnabled.value = false
    }

    /**
     * Prepares a Keystore DECRYPT Cipher initialized with the stored IV for
     * passing into [BiometricHelper.showCryptoPrompt]. Handles
     * [android.security.keystore.KeyPermanentlyInvalidatedException] by clearing
     * the invalidated biometric enrollment cleanly.
     */
    fun prepareSafeFolderBiometricUnlockCipher(): BiometricCipherResult {
        if (!BiometricHelper.isBiometricAvailable(appContext)) {
            return BiometricCipherResult.Unavailable("Biometric unlock is not available on this device.")
        }
        val wrapped = try {
            if (!secureSettings.getBooleanStrict(KEY_SAFE_FOLDER_BIOMETRIC, false)) {
                return BiometricCipherResult.Unavailable("Biometric unlock is disabled.")
            }
            secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_BIOMETRIC)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable preparing vault biometric cipher", e)
            return BiometricCipherResult.Unavailable(STORAGE_UNAVAILABLE_ERROR)
        }
        val iv = wrapped?.let { encryptionManager.extractBiometricWrapIv(it) }
            ?: return BiometricCipherResult.Unavailable(
                "Biometric unlock needs to be re-enabled after unlocking with your PIN."
            )

        val result = BiometricHelper.createDecryptCipher(VAULT_BIOMETRIC_KEY_ALIAS, iv)
        if (result is BiometricCipherResult.KeyInvalidated) {
            disableSafeFolderBiometric()
        }
        return result
    }

    /**
     * Unlocks the Safe Folder ONLY when [authenticatedCipher] (from
     * BiometricPrompt's CryptoObject) cryptographically unwraps the stored
     * 256-bit DEK. Without a hardware-authenticated Cipher, the DEK cannot be
     * recovered.
     */
    fun unlockSafeFolderWithBiometric(authenticatedCipher: Cipher): Boolean {
        val wrapped = try {
            if (!secureSettings.getBooleanStrict(KEY_SAFE_FOLDER_BIOMETRIC, false)) return false
            secureSettings.getStringStrict(KEY_VAULT_WRAPPED_DEK_BIOMETRIC) ?: return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable unlocking vault with biometric; failing closed", e)
            _safePinError.value = STORAGE_UNAVAILABLE_ERROR
            return false
        }

        val dek = encryptionManager.unwrapDekWithCipher(wrapped, authenticatedCipher) ?: return false
        return try {
            secureSessionManager.setSessionKey(dek)
            resetPinAttempts(SAFE_PIN_LOCKOUT_KEYS)
            _safePinError.value = null
            clearVaultBackgroundedAt()
            true
        } finally {
            dek.fill(0)
        }
    }

    // --- Vault 2.0: auto-lock after leaving the app ---

    fun setSafeFolderAutoLockMinutes(minutes: Int) {
        val value = if (minutes in AUTO_LOCK_OPTIONS) minutes else DEFAULT_AUTO_LOCK_MINUTES
        try {
            secureSettings.saveInt(KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES, value)
            _safeFolderAutoLockMinutes.value = value
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Cannot save auto-lock minutes: secure storage unavailable", e)
        }
    }

    /** Called by MainActivity when the app goes to the background. */
    fun recordVaultBackgroundedAt() {
        try {
            secureSettings.saveLong(KEY_VAULT_BACKGROUNDED_AT, SystemClock.elapsedRealtime())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not persist vault backgrounded timestamp", e)
        }
    }

    /**
     * L6 + H22: reads the stored backgrounded moment on the monotonic clock
     * using [SecureSettings.getLongStrict]. Fails closed (returns 1L so
     * [checkVaultAutoLock] locks the vault) if secure storage is unavailable.
     */
    private fun readBackgroundedElapsed(): Long {
        val stored = try {
            secureSettings.getLongStrict(KEY_VAULT_BACKGROUNDED_AT, 0L)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Secure storage unavailable reading vault backgrounded time; failing closed", e)
            return 1L
        }
        if (stored == 0L || stored < LEGACY_WALL_CLOCK_THRESHOLD_MS) return stored
        val elapsed = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - stored)
        val migrated = elapsed.coerceAtLeast(SystemClock.elapsedRealtime())
        try {
            secureSettings.saveLong(KEY_VAULT_BACKGROUNDED_AT, migrated)
        } catch (_: IllegalStateException) {
        }
        return migrated
    }

    private fun clearVaultBackgroundedAt() {
        try {
            secureSettings.saveLong(KEY_VAULT_BACKGROUNDED_AT, 0L)
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * H21: returns true only when a Safe Folder PIN is configured AND either
     * the vault session is unlocked or Safe Folder PIN lock was explicitly
     * disabled. Fails closed when secure storage is unavailable because both
     * [isSafeFolderPinSet] and [isSafeFolderLockEnabled] fail closed to true
     * while [isSecureModeActive] starts false.
     */
    fun isSafeFolderAccessAllowed(
        isPinSet: Boolean = _isSafeFolderPinSet.value,
        isLockEnabled: Boolean = _isSafeFolderLockEnabled.value,
        isSessionUnlocked: Boolean = isSecureModeActive.value
    ): Boolean {
        return isPinSet && (!isLockEnabled || isSessionUnlocked)
    }

    /**
     * Checked when the safe-folder screen (re)appears. If the app was in the
     * background longer than the auto-lock timeout, locks the vault and
     * returns true so the UI can navigate away.
     */
    fun checkVaultAutoLock(): Boolean {
        val backgroundedAt = readBackgroundedElapsed()
        if (backgroundedAt == 0L) return false
        clearVaultBackgroundedAt()
        if (!_isSafeFolderLockEnabled.value) return false
        val minutes = _safeFolderAutoLockMinutes.value
        if (minutes == AUTO_LOCK_NEVER) return false
        val now = SystemClock.elapsedRealtime()
        if (backgroundedAt == 1L || now < backgroundedAt || now - backgroundedAt > minutes * 60_000L) {
            lockSecureMode()
            return true
        }
        return false
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.isEqual(aBytes, bBytes)
        } finally {
            aBytes.fill(0)
            bBytes.fill(0)
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hexChars = "0123456789abcdef"
        val result = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            result[i * 2] = hexChars[v ushr 4]
            result[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(result)
    }
}

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * C8: resolves the single Activity-scoped [SecurityViewModel] so MainActivity,
 * NavGraph, MasterPasswordScreen, SettingsScreen, SecureStorageScreen, and
 * SafeFolderUnlockDialog all share one lock/security state instance instead of
 * separate NavBackStackEntry-scoped instances.
 */
@Composable
fun activitySecurityViewModel(): SecurityViewModel {
    val activity = LocalContext.current.findComponentActivity()
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}
