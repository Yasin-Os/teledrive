package com.awd.teledrive.ui.screens.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.core.BiometricHelper
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val secureSessionManager: SecureSessionManager,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val KEY_APP_LOCK_PIN = "app_lock_pin_code_v2"
        private const val KEY_APP_LOCK_ENABLED = "security_enabled"
        private const val KEY_APP_BIOMETRIC = "biometric_enabled"

        private const val KEY_SAFE_FOLDER_PIN = "safe_folder_pin_code_v2"
        private const val KEY_SAFE_FOLDER_LOCK_ENABLED = "safe_folder_lock_enabled_v2"

        // Vault 2.0: biometric unlock for the safe folder (opt-in, PIN stays
        // the fallback), auto-lock after leaving the app, and the timestamp
        // of the last backgrounding (written by MainActivity on ON_PAUSE).
        private const val KEY_SAFE_FOLDER_BIOMETRIC = "safe_folder_biometric_enabled"
        private const val KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES = "safe_folder_auto_lock_minutes"
        private const val KEY_VAULT_BACKGROUNDED_AT = "safe_folder_backgrounded_at_ms"

        /** Auto-lock choice meaning "never lock on background". */
        const val AUTO_LOCK_NEVER = -1
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        private val AUTO_LOCK_OPTIONS = setOf(1, 5, 15, 30, AUTO_LOCK_NEVER)

        // PIN brute-force protection: 5 wrong attempts lock the PIN for
        // 30 seconds, doubling on every further lockout (max 5 minutes).
        // Counters survive process death (stored in SecureSettings), so
        // restarting the app does not reset the lockout.
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

    // ১. পুরো অ্যাপ লক স্টেট (Settings -> Security)
    private val _isPasswordSet = MutableStateFlow(!secureSettings.getString(KEY_APP_LOCK_PIN).isNullOrEmpty())
    val isPasswordSet = _isPasswordSet.asStateFlow()

    private val _isSecurityEnabled = MutableStateFlow(
        secureSettings.getBoolean(KEY_APP_LOCK_ENABLED, false) && !secureSettings.getString(KEY_APP_LOCK_PIN).isNullOrEmpty()
    )
    val isSecurityEnabled = _isSecurityEnabled.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(secureSettings.getBoolean(KEY_APP_BIOMETRIC, false))
    val isBiometricEnabled = _isBiometricEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(
        secureSettings.getBoolean(KEY_APP_LOCK_ENABLED, false) && !secureSettings.getString(KEY_APP_LOCK_PIN).isNullOrEmpty()
    )
    val isLocked = _isLocked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // PIN lockout countdowns (seconds left, 0 = not locked), for the UI.
    private val _appPinLockoutRemaining = MutableStateFlow(0L)
    val appPinLockoutRemainingSeconds = _appPinLockoutRemaining.asStateFlow()

    private val _safePinLockoutRemaining = MutableStateFlow(0L)
    val safePinLockoutRemainingSeconds = _safePinLockoutRemaining.asStateFlow()

    private val _safePinError = MutableStateFlow<String?>(null)
    val safePinError = _safePinError.asStateFlow()

    private var appPinTicker: Job? = null
    private var safePinTicker: Job? = null

    init {
        // Resume the countdown if the app was restarted mid-lockout.
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
        }
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
        }
    }

    private fun isPinLocked(keys: PinLockoutKeys): Boolean {
        return secureSettings.getLong(keys.lockoutUntil, 0L) > System.currentTimeMillis()
    }

    private fun pinLockoutRemainingSeconds(keys: PinLockoutKeys): Long {
        return ((secureSettings.getLong(keys.lockoutUntil, 0L) - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0L)
    }

    private fun lockoutMessage(keys: PinLockoutKeys): String {
        return "Too many wrong attempts. Try again in ${pinLockoutRemainingSeconds(keys)}s"
    }

    private fun recordFailedPinAttempt(keys: PinLockoutKeys, flow: MutableStateFlow<Long>, startTicker: () -> Unit) {
        val attempts = secureSettings.getInt(keys.attempts, 0) + 1
        if (attempts >= MAX_PIN_ATTEMPTS) {
            val count = secureSettings.getInt(keys.lockoutCount, 0)
            val duration = minOf(BASE_LOCKOUT_MS * (1L shl minOf(count, 4)), MAX_LOCKOUT_MS)
            secureSettings.saveLong(keys.lockoutUntil, System.currentTimeMillis() + duration)
            secureSettings.saveInt(keys.lockoutCount, count + 1)
            secureSettings.saveInt(keys.attempts, 0)
            startTicker()
            flow.value = pinLockoutRemainingSeconds(keys)
        } else {
            secureSettings.saveInt(keys.attempts, attempts)
        }
    }

    private fun resetPinAttempts(keys: PinLockoutKeys) {
        secureSettings.saveInt(keys.attempts, 0)
        secureSettings.saveInt(keys.lockoutCount, 0)
        secureSettings.saveLong(keys.lockoutUntil, 0L)
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

    // ২. Safe Folder-এর সম্পূর্ণ আলাদা ও স্বতন্ত্র পিন লক স্টেট
    private val _isSafeFolderPinSet = MutableStateFlow(!secureSettings.getString(KEY_SAFE_FOLDER_PIN).isNullOrEmpty())
    val isSafeFolderPinSet = _isSafeFolderPinSet.asStateFlow()

    private val _isSafeFolderLockEnabled = MutableStateFlow(
        secureSettings.getBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, true)
    )
    val isSafeFolderLockEnabled = _isSafeFolderLockEnabled.asStateFlow()

    val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

    // Vault 2.0: biometric unlock for the safe folder (opt-in).
    private val _isSafeFolderBiometricEnabled = MutableStateFlow(
        secureSettings.getBoolean(KEY_SAFE_FOLDER_BIOMETRIC, false)
    )
    val isSafeFolderBiometricEnabled = _isSafeFolderBiometricEnabled.asStateFlow()

    // Vault 2.0: auto-lock after leaving the app, in minutes (-1 = never).
    private val _safeFolderAutoLockMinutes = MutableStateFlow(
        secureSettings.getInt(KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES, DEFAULT_AUTO_LOCK_MINUTES)
    )
    val safeFolderAutoLockMinutes = _safeFolderAutoLockMinutes.asStateFlow()

    // --- Whole App Lock Functions ---
    fun setSecurityEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_APP_LOCK_ENABLED, enabled)
        _isSecurityEnabled.value = enabled
        if (!enabled) {
            _isLocked.value = false
        }
    }

    fun setLocked(locked: Boolean) {
        if (_isSecurityEnabled.value && _isPasswordSet.value) {
            _isLocked.value = locked
        }
    }

    fun setPassword(pin: String) {
        secureSettings.saveString(KEY_APP_LOCK_PIN, pin)
        _isPasswordSet.value = true
        setSecurityEnabled(true)
        _isLocked.value = false
        _error.value = null
    }

    fun changeAppLockPin(oldPin: String, newPin: String): Boolean {
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            _error.value = lockoutMessage(APP_PIN_LOCKOUT_KEYS)
            return false
        }
        val current = secureSettings.getString(KEY_APP_LOCK_PIN)
        return if (current == null || current == oldPin) {
            resetPinAttempts(APP_PIN_LOCKOUT_KEYS)
            secureSettings.saveString(KEY_APP_LOCK_PIN, newPin)
            _isPasswordSet.value = true
            true
        } else {
            recordFailedPinAttempt(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining) {
                appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
            }
            _error.value = if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) lockoutMessage(APP_PIN_LOCKOUT_KEYS) else "Incorrect PIN"
            false
        }
    }

    fun verifyPassword(pin: String): Boolean {
        if (isPinLocked(APP_PIN_LOCKOUT_KEYS)) {
            _error.value = lockoutMessage(APP_PIN_LOCKOUT_KEYS)
            if (appPinTicker?.isActive != true) {
                appPinTicker = startLockoutTicker(APP_PIN_LOCKOUT_KEYS, _appPinLockoutRemaining)
            }
            return false
        }
        val stored = secureSettings.getString(KEY_APP_LOCK_PIN)
        val isValid = stored != null && stored == pin
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
        return isValid
    }

    fun unlockAppWithBiometric() {
        _error.value = null
        _isLocked.value = false
    }

    fun setBiometricEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_APP_BIOMETRIC, enabled)
        _isBiometricEnabled.value = enabled
    }

    // --- Safe Folder Independent PIN Functions (শুধুমাত্র পিন কোড, অ্যাপ লকের সাথে কোনো সম্পর্ক নেই) ---
    fun setupSafeFolderPin(pin: String) {
        secureSettings.saveString(KEY_SAFE_FOLDER_PIN, pin)
        secureSettings.saveBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, true)
        _isSafeFolderPinSet.value = true
        _isSafeFolderLockEnabled.value = true
        secureSessionManager.setSessionPassword(pin)
        clearVaultBackgroundedAt()
    }

    fun verifySafeFolderPin(pin: String): Boolean {
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            _safePinError.value = lockoutMessage(SAFE_PIN_LOCKOUT_KEYS)
            if (safePinTicker?.isActive != true) {
                safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
            }
            return false
        }
        val stored = secureSettings.getString(KEY_SAFE_FOLDER_PIN)
        return if (stored != null && stored == pin) {
            resetPinAttempts(SAFE_PIN_LOCKOUT_KEYS)
            _safePinError.value = null
            secureSessionManager.setSessionPassword(pin)
            clearVaultBackgroundedAt()
            true
        } else {
            recordFailedPinAttempt(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining) {
                safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
            }
            _safePinError.value = if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) lockoutMessage(SAFE_PIN_LOCKOUT_KEYS) else null
            false
        }
    }

    fun changeSafeFolderPin(oldPin: String, newPin: String): Boolean {
        if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) {
            _safePinError.value = lockoutMessage(SAFE_PIN_LOCKOUT_KEYS)
            return false
        }
        val stored = secureSettings.getString(KEY_SAFE_FOLDER_PIN)
        return if (stored == null || stored == oldPin) {
            resetPinAttempts(SAFE_PIN_LOCKOUT_KEYS)
            secureSettings.saveString(KEY_SAFE_FOLDER_PIN, newPin)
            _isSafeFolderPinSet.value = true
            secureSessionManager.setSessionPassword(newPin)
            clearVaultBackgroundedAt()
            true
        } else {
            recordFailedPinAttempt(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining) {
                safePinTicker = startLockoutTicker(SAFE_PIN_LOCKOUT_KEYS, _safePinLockoutRemaining)
            }
            _safePinError.value = if (isPinLocked(SAFE_PIN_LOCKOUT_KEYS)) lockoutMessage(SAFE_PIN_LOCKOUT_KEYS) else null
            false
        }
    }

    fun setSafeFolderLockEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, enabled)
        _isSafeFolderLockEnabled.value = enabled
    }

    fun unlockSecureMode(password: String): Boolean = verifySafeFolderPin(password)

    fun lockSecureMode() {
        secureSessionManager.clearSession()
    }

    // --- Vault 2.0: biometric unlock for the safe folder ---
    fun setSafeFolderBiometricEnabled(enabled: Boolean) {
        secureSettings.saveBoolean(KEY_SAFE_FOLDER_BIOMETRIC, enabled)
        _isSafeFolderBiometricEnabled.value = enabled
    }

    /**
     * Whether the safe folder can be unlocked with biometrics right now:
     * the user opted in, a safe-folder PIN exists to unlock with, and this
     * device can do a strong biometric check. PIN-only otherwise, never a
     * crash on devices without biometrics.
     */
    fun unlockWithBiometric(): Boolean {
        return _isSafeFolderBiometricEnabled.value &&
            _isSafeFolderPinSet.value &&
            BiometricHelper.isBiometricAvailable(appContext)
    }

    /**
     * Call only after a successful BiometricPrompt. Unlocks the vault the
     * same way the PIN does: the stored safe-folder PIN becomes the session
     * password (so decrypt-on-open keeps working), without ever showing the
     * PIN to the UI. Biometrics gate the stored credential; they do not
     * replace the PIN, which stays the fallback.
     */
    fun unlockSafeFolderWithBiometric(): Boolean {
        if (!unlockWithBiometric()) return false
        val storedPin = secureSettings.getString(KEY_SAFE_FOLDER_PIN) ?: return false
        secureSessionManager.setSessionPassword(storedPin)
        clearVaultBackgroundedAt()
        return true
    }

    // --- Vault 2.0: auto-lock after leaving the app ---
    fun setSafeFolderAutoLockMinutes(minutes: Int) {
        val value = if (minutes in AUTO_LOCK_OPTIONS) minutes else DEFAULT_AUTO_LOCK_MINUTES
        secureSettings.saveInt(KEY_SAFE_FOLDER_AUTO_LOCK_MINUTES, value)
        _safeFolderAutoLockMinutes.value = value
    }

    /** Called by MainActivity when the app goes to the background. */
    fun recordVaultBackgroundedAt() {
        secureSettings.saveLong(KEY_VAULT_BACKGROUNDED_AT, System.currentTimeMillis())
    }

    private fun clearVaultBackgroundedAt() {
        secureSettings.saveLong(KEY_VAULT_BACKGROUNDED_AT, 0L)
    }

    /**
     * Checked when the safe-folder screen (re)appears. If the app was in the
     * background longer than the auto-lock timeout, locks the vault and
     * returns true so the UI can navigate away. Consumes the background
     * timestamp either way, so each backgrounding is judged once. Never
     * locks while the vault is in the foreground: this only runs on resume
     * after a backgrounding, and a fresh PIN/biometric unlock clears the
     * timestamp. No-op when the safe-folder lock is off or set to Never.
     */
    fun checkVaultAutoLock(): Boolean {
        val backgroundedAt = secureSettings.getLong(KEY_VAULT_BACKGROUNDED_AT, 0L)
        if (backgroundedAt == 0L) return false
        clearVaultBackgroundedAt()
        if (!_isSafeFolderLockEnabled.value) return false
        val minutes = _safeFolderAutoLockMinutes.value
        if (minutes == AUTO_LOCK_NEVER) return false
        if (System.currentTimeMillis() - backgroundedAt > minutes * 60_000L) {
            lockSecureMode()
            return true
        }
        return false
    }
}
