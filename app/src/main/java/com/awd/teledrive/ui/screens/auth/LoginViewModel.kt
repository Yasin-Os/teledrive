package com.awd.teledrive.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

sealed class LoginUiState {
    object InitialSetup : LoginUiState()
    object EnteringCode : LoginUiState()
    object EnteringPassword : LoginUiState()
    object Success : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.InitialSetup)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _submittedPhone = MutableStateFlow("")
    val submittedPhone: StateFlow<String> = _submittedPhone.asStateFlow()

    private val _codeResentMessage = MutableStateFlow<String?>(null)
    val codeResentMessage: StateFlow<String?> = _codeResentMessage.asStateFlow()

    init {
        secureSettings.saveBoolean("active_provider_google", false)
        secureSettings.saveBoolean("active_provider_telegram", true)

        viewModelScope.launch {
            telegramClient.authState.collect { state ->
                when (state) {
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        if (_uiState.value !is LoginUiState.InitialSetup && _submittedPhone.value.isEmpty()) {
                            _uiState.value = LoginUiState.InitialSetup
                        }
                    }
                    is TdApi.AuthorizationStateWaitCode -> {
                        _uiState.value = LoginUiState.EnteringCode
                        _isProcessing.value = false
                    }
                    is TdApi.AuthorizationStateWaitPassword -> {
                        _uiState.value = LoginUiState.EnteringPassword
                        _isProcessing.value = false
                    }
                    is TdApi.AuthorizationStateReady -> {
                        secureSettings.saveBoolean("active_provider_telegram", true)
                        _uiState.value = LoginUiState.Success
                        _isProcessing.value = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun onLoginSubmit(apiIdStr: String, apiHash: String, phoneNumber: String) {
        val apiId = apiIdStr.toIntOrNull() ?: 94575
        if (apiHash.isBlank() || phoneNumber.isBlank()) {
            _authError.value = "Please enter a valid phone number"
            return
        }

        _isProcessing.value = true
        _authError.value = null
        _submittedPhone.value = phoneNumber

        secureSettings.saveApiCredentials(apiId, apiHash)

        viewModelScope.launch {
            try {
                telegramClient.setApiCredentials(apiId, apiHash)
                telegramClient.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) { result ->
                    if (result is TdApi.Error) {
                        _isProcessing.value = false
                        _authError.value = result.message
                    }
                }
            } catch (e: Exception) {
                _isProcessing.value = false
                _authError.value = e.message
            }
        }
    }

    fun onCodeSubmit(code: String) {
        if (code.length < 5) {
            _authError.value = "Enter a valid 5-digit code"
            return
        }
        _isProcessing.value = true
        _authError.value = null

        viewModelScope.launch {
            telegramClient.send(TdApi.CheckAuthenticationCode(code)) { result ->
                if (result is TdApi.Error) {
                    _isProcessing.value = false
                    _authError.value = result.message
                }
            }
        }
    }

    fun onPasswordSubmit(password: String) {
        if (password.isBlank()) {
            _authError.value = "Enter your 2FA password"
            return
        }
        _isProcessing.value = true
        _authError.value = null

        viewModelScope.launch {
            telegramClient.send(TdApi.CheckAuthenticationPassword(password)) { result ->
                if (result is TdApi.Error) {
                    _isProcessing.value = false
                    _authError.value = result.message
                }
            }
        }
    }

    fun onResendCode() {
        _isProcessing.value = true
        viewModelScope.launch {
            telegramClient.send(TdApi.ResendAuthenticationCode()) { result ->
                _isProcessing.value = false
                if (result is TdApi.Error) {
                    _codeResentMessage.value = "Failed to resend: ${result.message}"
                } else {
                    _codeResentMessage.value = "Verification code resent"
                }
            }
        }
    }

    fun onBackToPhoneInput() {
        _isProcessing.value = false
        _authError.value = null
        _uiState.value = LoginUiState.InitialSetup
        _submittedPhone.value = ""
    }

    fun clearResentMessage() {
        _codeResentMessage.value = null
    }
}
