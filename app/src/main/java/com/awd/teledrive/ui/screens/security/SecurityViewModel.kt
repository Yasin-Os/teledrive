package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val secureSettings: SecureSettings,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    companion object {
        private const val KEY_APP_LOCK_PIN = "app_lock_pin_code_v2"
        private const val KEY_APP_LOCK_ENABLED = "security_enabled"
        private const val KEY_APP_BIOMETRIC = "biometric_enabled"

        private const val KEY_SAFE_FOLDER_PIN = "safe_folder_pin_code_v2"
        private const val KEY_SAFE_FOLDER_LOCK_ENABLED = "safe_folder_lock_enabled_v2"
    }

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

    // ২. Safe Folder-এর সম্পূর্ণ আলাদা ও স্বতন্ত্র পিন লক স্টেট
    private val _isSafeFolderPinSet = MutableStateFlow(!secureSettings.getString(KEY_SAFE_FOLDER_PIN).isNullOrEmpty())
    val isSafeFolderPinSet = _isSafeFolderPinSet.asStateFlow()

    private val _isSafeFolderLockEnabled = MutableStateFlow(
        secureSettings.getBoolean(KEY_SAFE_FOLDER_LOCK_ENABLED, true)
    )
    val isSafeFolderLockEnabled = _isSafeFolderLockEnabled.asStateFlow()

    val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

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
        val current = secureSettings.getString(KEY_APP_LOCK_PIN)
        return if (current == null || current == oldPin) {
            secureSettings.saveString(KEY_APP_LOCK_PIN, newPin)
            _isPasswordSet.value = true
            true
        } else {
            false
        }
    }

    fun verifyPassword(pin: String): Boolean {
        val stored = secureSettings.getString(KEY_APP_LOCK_PIN)
        val isValid = stored != null && stored == pin
        if (!isValid) {
            _error.value = "Incorrect PIN"
        } else {
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
    }

    fun verifySafeFolderPin(pin: String): Boolean {
        val stored = secureSettings.getString(KEY_SAFE_FOLDER_PIN)
        return if (stored != null && stored == pin) {
            secureSessionManager.setSessionPassword(pin)
            true
        } else {
            false
        }
    }

    fun changeSafeFolderPin(oldPin: String, newPin: String): Boolean {
        val stored = secureSettings.getString(KEY_SAFE_FOLDER_PIN)
        return if (stored == null || stored == oldPin) {
            secureSettings.saveString(KEY_SAFE_FOLDER_PIN, newPin)
            _isSafeFolderPinSet.value = true
            secureSessionManager.setSessionPassword(newPin)
            true
        } else {
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

    fun unlockWithBiometric(): Boolean = false
}
