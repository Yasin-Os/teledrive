package com.awd.teledrive.data.secure

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSessionManager @Inject constructor() {
    companion object {
        // The decrypted master password is cleared from memory after this
        // long without use, so it does not sit in RAM indefinitely.
        const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L
    }

    private val _decryptedPassword = MutableStateFlow<String?>(null)
    val decryptedPassword = _decryptedPassword.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clearJob: Job? = null

    fun setSessionPassword(password: String) {
        _decryptedPassword.value = password
        scheduleAutoClear()
    }

    /** Call when the session password is used, to reset the inactivity timer. */
    fun touchSession() {
        if (_decryptedPassword.value != null) {
            scheduleAutoClear()
        }
    }

    fun clearSession() {
        clearJob?.cancel()
        clearJob = null
        _decryptedPassword.value = null
    }

    fun isUnlocked() = _decryptedPassword.value != null

    private fun scheduleAutoClear() {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            _decryptedPassword.value = null
        }
    }
}
