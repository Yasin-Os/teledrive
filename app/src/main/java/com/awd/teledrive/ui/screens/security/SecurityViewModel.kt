package com.awd.teledrive.ui.screens.security

import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.MasterPasswordService
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val masterPasswordService: MasterPasswordService,
    private val secureSettings: SecureSettings,
    private val secureSessionManager: SecureSessionManager
) : ViewModel() {

    private val SAFE_FOLDER_PASS_KEY = "safe_folder_separate_password"

    private val _isPasswordSet = MutableStateFlow(
        !secureSettings.getString(SAFE_FOLDER_PASS_KEY).isNullOrEmpty() || masterPasswordService.isPasswordSet()
    )
    val isPasswordSet = _isPasswordSet.asStateFlow()

    // এটি শুধুমাত্র পুরো অ্যাপ লক করার সুইচ (Safe Folder-এর সাথে এটি চালু হবে না)
    private val _isSecurityEnabled = MutableStateFlow(secureSettings.isSecurityEnabled())
    val isSecurityEnabled = _isSecurityEnabled.asStateFlow()

    private val _isBiometricEnabled = MutableStateFlow(secureSettings.getBoolean("biometric_enabled"))
    val isBiometricEnabled = _isBiometricEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setSecurityEnabled(enabled: Boolean) {
        secureSettings.setSecurityEnabled(enabled)
        _isSecurityEnabled.value = enabled
        if (!enabled) {
            _isLocked.value = false
        }
    }

    fun setLocked(locked: Boolean) {
        // শুধুমাত্র সেটিংসে পুরো অ্যাপ লক (Master Password) চালু থাকলেই অ্যাপ লক হবে
        if (_isSecurityEnabled.value && masterPasswordService.isPasswordSet()) {
            _isLocked.value = locked
        }
    }

    fun unlockSecureMode(password: String): Boolean {
        val savedSafePass = secureSettings.getString(SAFE_FOLDER_PASS_KEY)
        val isMatch = if (!savedSafePass.isNullOrEmpty()) {
            savedSafePass == password
        } else {
            masterPasswordService.verifyPassword(password)
        }

        return if (isMatch) {
            secureSettings.saveString(SAFE_FOLDER_PASS_KEY, password)
            secureSessionManager.setSessionPassword(password)
            _isPasswordSet.value = true
            true
        } else {
            false
        }
    }

    fun lockSecureMode() {
        secureSessionManager.clearSession()
    }

    fun unlockWithBiometric(): Boolean {
        val savedSafePass = secureSettings.getString(SAFE_FOLDER_PASS_KEY) ?: masterPasswordService.getStoredPassword()
        return if (!savedSafePass.isNullOrEmpty()) {
            secureSessionManager.setSessionPassword(savedSafePass)
            true
        } else false
    }

    val isSecureModeActive = secureSessionManager.decryptedPassword.map { it != null }

    // Safe Folder বা পাসওয়ার্ড সেট করলে পুরো অ্যাপ লক (setSecurityEnabled) চালু হবে না
    fun setPassword(password: String) {
        secureSettings.saveString(SAFE_FOLDER_PASS_KEY, password)
        masterPasswordService.setPassword(password)
        _isPasswordSet.value = true
    }

    fun setBiometricEnabled(enabled: Boolean) {
        secureSettings.saveBoolean("biometric_enabled", enabled)
        _isBiometricEnabled.value = enabled
    }

    fun verifyPassword(password: String): Boolean {
        val isValid = masterPasswordService.verifyPassword(password) ||
                      secureSettings.getString(SAFE_FOLDER_PASS_KEY) == password
        if (!isValid) {
            _error.value = "Incorrect password"
        } else {
            _error.value = null
            _isLocked.value = false
        }
        return isValid
    }
}
