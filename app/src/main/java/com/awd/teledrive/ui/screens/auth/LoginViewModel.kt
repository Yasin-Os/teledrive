package com.awd.teledrive.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awd.teledrive.data.repository.AuthRepository
import com.awd.teledrive.data.repository.AuthState
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
    object Loading : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
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
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthState.Unauthenticated -> {
                        if (_uiState.value !is LoginUiState.InitialSetup && _submittedPhone.value.isEmpty()) {
                            _uiState.value = LoginUiState.InitialSetup
                        }
                    }
                    is AuthState.WaitingForCode -> {
                        _uiState.value = LoginUiState.EnteringCode
                        _isProcessing.value = false
                    }
                    is AuthState.WaitingForPassword -> {
                        _uiState.value = LoginUiState.EnteringPassword
                        _isProcessing.value = false
                    }
                    is AuthState.Authenticated -> {
                        secureSettings.saveBoolean("active_provider_telegram", true)
                        _uiState.value = LoginUiState.Success
                        _isProcessing.value = false
                    }
                    is AuthState.Error -> {
                        _authError.value = state.message
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

        authRepository.saveApiCredentials(apiId, apiHash)

        viewModelScope.launch {
            try {
                authRepository.submitPhoneNumber(phoneNumber) { result ->
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
            authRepository.submitCode(code) { result ->
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
            authRepository.submitPassword(password) { result ->
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
            authRepository.resendCode { result ->
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
