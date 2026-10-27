package com.awd.teledrive.data.repository

import android.content.Context
import android.os.Build
import com.awd.teledrive.core.Config
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState = _authState.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

    private val _codeResentMessage = MutableStateFlow<String?>(null)
    val codeResentMessage = _codeResentMessage.asStateFlow()

    private fun getApiId(): Int {
        val stored = secureSettings.getInt("api_id", 0)
        if (stored != 0) return stored
        if (secureSettings.getBoolean("api_explicitly_cleared", false)) return 0
        return Config.API_ID
    }

    private fun getApiHash(): String {
        val stored = secureSettings.getString("api_hash", "")
        if (!stored.isNullOrEmpty()) return stored
        if (secureSettings.getBoolean("api_explicitly_cleared", false)) return ""
        return Config.API_HASH
    }

    init {
        scope.launch {
            telegramClient.send(TdApi.GetAuthorizationState()) { result ->
                if (result is TdApi.AuthorizationState) {
                    _authState.value = result
                    handleAuthorizationState(result)
                }
            }

            telegramClient.updates.collect { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    _authState.value = update.authorizationState
                    handleAuthorizationState(update.authorizationState)
                }
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (isApiConfigured() && !secureSettings.getBoolean("api_explicitly_cleared", false)) {
                    sendTdlibParameters(getApiId(), getApiHash())
                }
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                telegramClient.send(TdApi.RegisterUser("User", "", false))
            }
        }
    }

    private fun sendTdlibParameters(apiId: Int, apiHash: String) {
        val deviceManufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val deviceModelName = "$deviceManufacturer ${Build.MODEL}".trim()
        val sysLang = Locale.getDefault().language.ifEmpty { "en" }

        val parameters = TdApi.SetTdlibParameters(
            false,
            File(context.filesDir, "tdlib").absolutePath,
            File(context.cacheDir, "tdlib_files").absolutePath,
            null,
            true,
            true,
            true,
            false,
            apiId,
            apiHash,
            sysLang,
            deviceModelName,
            "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            "11.5.3 (54802)"
        )

        telegramClient.send(parameters) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            } else {
                _authError.value = null
                settingsRepository.applyCacheSettings()
            }
        }
    }

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        _authError.value = null
        secureSettings.saveInt("api_id", apiId)
        secureSettings.saveString("api_hash", apiHash)
        secureSettings.saveBoolean("api_explicitly_cleared", false)

        val currentState = _authState.value
        if (currentState is TdApi.AuthorizationStateWaitTdlibParameters) {
            sendTdlibParameters(apiId, apiHash)
        }
    }

    fun clearApiCredentials() {
        _authError.value = null
        secureSettings.saveInt("api_id", 0)
        secureSettings.saveString("api_hash", "")
        secureSettings.saveBoolean("api_explicitly_cleared", true)
        _authState.value = TdApi.AuthorizationStateWaitTdlibParameters()
        telegramClient.send(TdApi.LogOut())
    }

    fun isApiConfigured(): Boolean {
        return getApiId() != 0 && getApiHash().isNotEmpty()
    }

    fun setPhoneNumber(phoneNumber: String) {
        _authError.value = null
        _codeResentMessage.value = null
        val authSettings = TdApi.PhoneNumberAuthenticationSettings(
            false,
            false,
            false,
            false,
            null,
            emptyArray()
        )
        telegramClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, authSettings)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun resendAuthenticationCode() {
        _authError.value = null
        telegramClient.send(TdApi.ResendAuthenticationCode()) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            } else {
                _codeResentMessage.value = "Verification code sent again"
            }
        }
    }

    fun returnToPhoneInput() {
        _authError.value = null
        _codeResentMessage.value = null
        _authState.value = TdApi.AuthorizationStateWaitPhoneNumber()
    }

    fun checkAuthenticationCode(code: String) {
        _authError.value = null
        telegramClient.send(TdApi.CheckAuthenticationCode(code)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun checkPassword(password: String) {
        _authError.value = null
        telegramClient.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
        }
    }

    fun setLocalError(message: String?) {
        _authError.value = if (message.isNullOrEmpty()) null else message
    }

    fun clearResentMessage() {
        _codeResentMessage.value = null
    }

    fun logout() {
        _authError.value = null
        telegramClient.send(TdApi.LogOut()) { result ->
            if (result is TdApi.Error) {
                _authError.value = result.message
            }
            telegramClient.send(TdApi.GetAuthorizationState()) { state ->
                if (state is TdApi.AuthorizationState) {
                    _authState.value = state
                    handleAuthorizationState(state)
                }
            }
        }
    }
}
