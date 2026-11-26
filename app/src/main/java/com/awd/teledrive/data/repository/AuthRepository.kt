package com.awd.teledrive.data.repository

import android.content.Context
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSessionManager
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    private val secureSessionManager: SecureSessionManager,
    private val driveRepository: dagger.Lazy<DriveRepository>,
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isLoggingOut = AtomicBoolean(false)
    private val logoutMutex = Mutex()

    // H24: the saved "authenticated" flag is not trusted as the
    // start state. A dead TDLib client could otherwise sit on a wrong
    // Authenticated forever without TDLib ever confirming it. Start at
    // Loading; the init block asks TDLib for its real authorization
    // state and handleAuthorizationState moves from there. The login
    // screen is not flashed: it only navigates on a non-Loading,
    // non-Success login state.
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _tdAuthState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val tdAuthState: StateFlow<TdApi.AuthorizationState?> = _tdAuthState.asStateFlow()

    init {
        scope.launch {
            telegramClient.updates.collect { update ->
                try {
                    if (update is TdApi.UpdateAuthorizationState) {
                        handleAuthorizationState(update.authorizationState)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("AuthRepository", "Error handling TDLib update", e)
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
                runCatching { secureSettings.saveBoolean("active_provider_telegram", false) }
                if (!isLoggingOut.get()) {
                    _authState.value = AuthState.Unauthenticated
                }
            }
            is TdApi.AuthorizationStateWaitCode -> {
                _authState.value = AuthState.WaitingForCode
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitingForPassword
            }
            is TdApi.AuthorizationStateReady -> {
                runCatching { secureSettings.saveBoolean("active_provider_telegram", true) }
                _authState.value = AuthState.Authenticated
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                // H24: the user must confirm on another device; TDLib
                // advances on its own once they do. Surface it instead
                // of hanging on the spinner.
                _authState.value = AuthState.Error("Please confirm this login on your other device")
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                // H24: a new account needs its Telegram registration
                // completed first; the app cannot do that step.
                _authState.value = AuthState.Error("Please complete Telegram registration first")
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                runCatching { secureSettings.saveBoolean("active_provider_telegram", false) }
                // H4: while logoutAndWait() is running, stay in Loading so
                // NavGraph does not prematurely tear down SettingsScreen before
                // LogOut + local wipe + client recreation finish.
                if (!isLoggingOut.get()) {
                    _authState.value = AuthState.Unauthenticated
                }
            }
            is TdApi.AuthorizationStateClosing -> {
                // Intermediate state before AuthorizationStateClosed.
            }
            is TdApi.AuthorizationStateClosed -> {
                runCatching { secureSettings.saveBoolean("active_provider_telegram", false) }
                if (!isLoggingOut.get()) {
                    // H4: if TDLib closed outside logoutAndWait() (e.g. session
                    // revoked from another device), clear session state and
                    // recreate the TDLib client so re-login works without
                    // force-stopping the app.
                    secureSessionManager.clearSession()
                    _authState.value = AuthState.Unauthenticated
                    scope.launch {
                        if (telegramClient.recreateClient()) {
                            telegramClient.send(TdApi.GetAuthorizationState()) { result ->
                                if (result is TdApi.AuthorizationState) {
                                    handleAuthorizationState(result)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // H24: never silently swallow an authorization state —
                // an unhandled one (e.g. WaitEncryptionKey) is logged
                // so a stuck login can be diagnosed instead of hanging.
                android.util.Log.w("AuthRepository", "Unhandled authorization state: $state")
            }
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
            if (!isLoggingOut.get()) {
                _authState.value = AuthState.Unauthenticated
            }
            return
        }

        // H4: if the TDLib client was closed, recreate it before sending parameters.
        if (!telegramClient.isClientAlive() || _tdAuthState.value is TdApi.AuthorizationStateClosed) {
            telegramClient.recreateClient()
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

    /**
     * H4: full logout sequence executed on the singleton repository scope so
     * UI navigation cannot cancel it mid-way:
     * 1. Wipe vault session DEK and auth flags immediately.
     * 2. Send TdApi.LogOut() and wait for TDLib to reach AuthorizationStateClosed
     *    (falling back to TdApi.Close() if LogOut returns an error, with a
     *    bounded timeout so offline logout never hangs forever).
     * 3. Clear all Room tables (drive_items, transfers, file_hashes,
     *    file_content_fts, recent_searches), DriveRepository storage chat ID /
     *    PREF_STORAGE_CHAT_ID, backup tracking prefs (backed_up_paths_v3), and
     *    decrypted preview temp files.
     * 4. Recreate the TDLib Client so immediate re-login in the same process
     *    succeeds without force-stopping the app.
     */
    suspend fun logoutAndWait() {
        scope.async {
            logoutMutex.withLock {
                isLoggingOut.set(true)
                try {
                    secureSessionManager.clearSession()
                    runCatching { secureSettings.saveBoolean("active_provider_google", false) }
                    runCatching { secureSettings.saveBoolean("active_provider_telegram", false) }
                    _authState.value = AuthState.Loading

                    if (telegramClient.isClientAlive() && _tdAuthState.value !is TdApi.AuthorizationStateClosed) {
                        val logoutResult = CompletableDeferred<TdApi.Object>()
                        telegramClient.send(TdApi.LogOut()) { result ->
                            logoutResult.complete(result)
                        }
                        val response = withTimeoutOrNull(5_000L) { logoutResult.await() }
                        if (response is TdApi.Error && _tdAuthState.value !is TdApi.AuthorizationStateClosed) {
                            telegramClient.send(TdApi.Close()) { _ -> }
                        }
                        withTimeoutOrNull(10_000L) {
                            _tdAuthState.first { it is TdApi.AuthorizationStateClosed }
                        }
                    }

                    driveRepository.get().clearAllLocalUserDataOnLogout()

                    if (_tdAuthState.value is TdApi.AuthorizationStateClosed) {
                        runCatching {
                            File("${context.filesDir.absolutePath}/tdlib").deleteRecursively()
                        }
                    }

                    _tdAuthState.value = null
                    if (telegramClient.recreateClient()) {
                        telegramClient.send(TdApi.GetAuthorizationState()) { result ->
                            if (result is TdApi.AuthorizationState) {
                                handleAuthorizationState(result)
                            }
                        }
                        // Give the fresh TDLib client a brief window to receive
                        // WaitTdlibParameters -> WaitPhoneNumber before UI restarts.
                        withTimeoutOrNull(3_000L) {
                            _tdAuthState.first {
                                it is TdApi.AuthorizationStateWaitPhoneNumber ||
                                    it is TdApi.AuthorizationStateWaitTdlibParameters
                            }
                        }
                    }
                } finally {
                    isLoggingOut.set(false)
                    _authState.value = AuthState.Unauthenticated
                }
            }
        }.await()
    }

    fun logout() {
        scope.launch { logoutAndWait() }
    }

    fun logoutAll() {
        logout()
    }
}
