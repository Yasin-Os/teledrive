package com.awd.teledrive.data.secure

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the active per-vault 256-bit Data Encryption Key (DEK) in a wipeable
 * [ByteArray] for the duration of an unlocked vault session. Never holds a
 * plaintext PIN or immutable [String] secret.
 *
 * Callers obtain a defensive copy via [getSessionKeyCopy] and MUST zero that
 * copy in a `finally` block once their crypto operation finishes.
 */
@Singleton
class SecureSessionManager @Inject constructor() {
    companion object {
        // The active vault DEK is wiped from memory after this long without
        // use, so it does not sit in RAM indefinitely.
        const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L
    }

    private val lock = Any()
    private var activeDek: ByteArray? = null
    private var sessionGeneration: Long = 0L

    private val _isSessionUnlocked = MutableStateFlow(false)
    val isSessionUnlocked: StateFlow<Boolean> = _isSessionUnlocked.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clearJob: Job? = null

    /**
     * Installs a defensive copy of [dek] as the active vault session key and
     * wipes any previous key from memory. Callers should zero their own [dek]
     * buffer after calling this method.
     */
    fun setSessionKey(dek: ByteArray) {
        require(dek.size == EncryptionManager.DEK_LENGTH_BYTE) {
            "Session DEK must be ${EncryptionManager.DEK_LENGTH_BYTE} bytes"
        }
        synchronized(lock) {
            activeDek?.fill(0)
            activeDek = dek.copyOf()
            sessionGeneration++
            _isSessionUnlocked.value = true
            scheduleAutoClearLocked(sessionGeneration)
        }
    }

    /**
     * Returns a defensive copy of the active 256-bit DEK, or null if the vault
     * is locked. Resets the inactivity timer when unlocked. Callers MUST wipe
     * the returned array (`copy.fill(0)`) in a `finally` block.
     */
    fun getSessionKeyCopy(): ByteArray? {
        synchronized(lock) {
            val dek = activeDek ?: return null
            sessionGeneration++
            scheduleAutoClearLocked(sessionGeneration)
            return dek.copyOf()
        }
    }

    /** Resets the inactivity timer while the vault session is active. */
    fun touchSession() {
        synchronized(lock) {
            if (activeDek != null) {
                sessionGeneration++
                scheduleAutoClearLocked(sessionGeneration)
            }
        }
    }

    /** Immediately zeroes the active DEK in memory and marks the vault locked. */
    fun clearSession() {
        synchronized(lock) {
            clearJob?.cancel()
            clearJob = null
            sessionGeneration++
            activeDek?.fill(0)
            activeDek = null
            _isSessionUnlocked.value = false
        }
    }

    fun isUnlocked(): Boolean = synchronized(lock) { activeDek != null }

    private fun scheduleAutoClearLocked(expectedGeneration: Long) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(SESSION_TIMEOUT_MS)
            synchronized(lock) {
                if (sessionGeneration == expectedGeneration) {
                    activeDek?.fill(0)
                    activeDek = null
                    clearJob = null
                    _isSessionUnlocked.value = false
                }
            }
        }
    }
}
