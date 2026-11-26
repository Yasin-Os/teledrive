package com.awd.teledrive.data.repository

import android.content.Context
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthState {
    object Loading : AuthState()
    object Unauthenticated : AuthState()
    object Authenticated : AuthState()
    object WaitingForCode : AuthState()
    object WaitingForPassword : AuthState()
    data class Error(val message: String) : AuthState()
}

@Singleton
class AuthRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ইউজার আগে থেকে লগইন করা থাকলে শুরু থেকেই Authenticated থাকবে, যাতে ভুল করেও LoginScreen না দেখায়
    private val _authState = MutableStateFlow<AuthState>(
        if (secureSettings.getBoolean("active_provider_telegram", false)) {
            AuthState.Authenticated
        } else {
            AuthState.Loading
        }
    )
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _tdAuthState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val tdAuthState: StateFlow<TdApi.AuthorizationState?> = _tdAuthState.asStateFlow()

    init {
        scope.launch {
            telegramClient.updates.collect { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    handleAuthorizationState(update.authorizationState)
                }
            }
        }
        telegramClient.send(TdApi.GetAuthorizationState()) { result ->
            if (result is TdApi.AuthorizationState) {
                handleAuthorizationState(result)
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState?) {
        _tdAuthState.value = state
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                sendTdlibParameters()
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                secureSettings.saveBoolean("active_provider_telegram", false)
                _authState.value = AuthState.Unauthenticated
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _authState.value = AuthState.WaitingForCode
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitingForPassword
            }
            is TdApi.AuthorizationStateReady -> {
                secureSettings.saveBoolean("active_provider_telegram", true)
                _authState.value = AuthState.Authenticated
            }
            is TdApi.AuthorizationStateClosed -> {
                secureSettings.saveBoolean("active_provider_telegram", false)
                _authState.value = AuthState.Unauthenticated
            }
            else -> {}
        }
    }

    fun sendTdlibParameters() {
        // No shared/default credentials: every user must use their own API
        // ID / API hash from https://my.telegram.org (entered on the login
        // screen or in Settings). Previously a hardcoded fallback credential
        // was used for every install, which shared one Telegram app identity
        // across all users and could be revoked or abused.
        // Users who already logged in keep working: their own credentials
        // were saved with saveApiCredentials() and are read back here.
        val apiId = secureSettings.getInt("api_id", 0)
        val apiHash = secureSettings.getString("api_hash", "")?.takeIf { it.isNotBlank() }
        if (apiId == 0 || apiHash == null) {
            android.util.Log.e(
                "AuthRepository",
                "Telegram API credentials are not configured; waiting for the user to enter their own API ID and API hash"
            )
            // No credentials yet: this is the normal first-run state, not
            // an error. Emit Unauthenticated so the login screen shows
            // the API ID / API hash setup form. Emitting Error here left
            // new users stuck on an infinite spinner: LoginViewModel
            // never leaves LoginUiState.Loading on Error.
            _authState.value = AuthState.Unauthenticated
            return
        }

        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory = "${context.filesDir.absolutePath}/tdlib"
            useMessageDatabase = true
            useSecretChats = true
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = "en"
            deviceModel = android.os.Build.MODEL ?: "Android"
            systemVersion = android.os.Build.VERSION.RELEASE ?: "1.0"
            applicationVersion = "2.0"
            // Encrypt the TDLib database (see resolveTdlibEncryptionKey).
            databaseEncryptionKey = resolveTdlibEncryptionKey()
        }
        telegramClient.send(params) { _ -> }
    }

    /**
     * Resolves the TDLib database encryption key. A stored key must be
     * supplied on every launch once the TDLib database is encrypted, so a
     * key created for a fresh database is reused afterwards. Adding a key to
     * an EXISTING unencrypted TDLib database would make it unreadable, so
     * existing installs keep the current behaviour (empty key) and only fresh
     * installs get an encrypted database. If the key cannot be stored, the
     * fresh install also falls back to the current unencrypted behaviour.
     */
    private fun resolveTdlibEncryptionKey(): ByteArray {
        secureSettings.getStoredTdlibEncryptionKey()?.let { return it }

        val dbDir = File("${context.filesDir.absolutePath}/tdlib")
        // M18: "any file in tdlib/" is not an existing database. A stray log
        // or partial download used to count as one, so a fresh install never
        // got an encrypted TDLib database. Only TDLib's own database files
        // count: td.binlog and db.sqlite (plus their journal/wal siblings).
        val hasExistingDb = dbDir.exists() &&
            (dbDir.listFiles()?.any { file ->
                file.name == "td.binlog" || file.name == "db.sqlite" ||
                    file.name.startsWith("td.binlog.") || file.name.startsWith("db.sqlite.")
            } == true)
        if (hasExistingDb) {
            return ByteArray(0)
        }
        // A fresh install: create the key, retrying briefly — the Keystore
        // can be momentarily unavailable. If it still cannot be stored, the
        // install stays unencrypted (login must not break), but say so
        // loudly: this is permanent for this install, because once TDLib
        // creates the database unencrypted, a key can never be added later.
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                return secureSettings.getOrCreateTdlibEncryptionKey()
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    runCatching { Thread.sleep(300L) }
                }
            }
        }
        android.util.Log.e(
            "AuthRepository",
            "Could not store TDLib encryption key: this fresh install will stay unencrypted permanently",
            lastError
        )
        return ByteArray(0)
    }

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        secureSettings.saveInt("api_id", apiId)
        secureSettings.saveString("api_hash", apiHash)
        sendTdlibParameters()
    }

    fun submitPhoneNumber(phone: String, onResult: (TdApi.Object) -> Unit = {}) {
        telegramClient.send(TdApi.SetAuthenticationPhoneNumber(phone, null), onResult)
    }

    fun submitCode(code: String, onResult: (TdApi.Object) -> Unit = {}) {
        telegramClient.send(TdApi.CheckAuthenticationCode(code), onResult)
    }

    fun submitPassword(password: String, onResult: (TdApi.Object) -> Unit = {}) {
        telegramClient.send(TdApi.CheckAuthenticationPassword(password), onResult)
    }

    fun resendCode(onResult: (TdApi.Object) -> Unit = {}) {
        telegramClient.send(TdApi.ResendAuthenticationCode(), onResult)
    }

    fun logout() {
        secureSettings.saveBoolean("active_provider_google", false)
        secureSettings.saveBoolean("active_provider_telegram", false)
        telegramClient.send(TdApi.LogOut()) { _ ->
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun logoutAll() {
        logout()
    }
}
