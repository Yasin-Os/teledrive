package com.awd.teledrive.data.repository

import android.content.Context
import com.awd.teledrive.data.remote.TelegramClient
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val secureSettings: SecureSettings,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) {
    val authState: Flow<TdApi.AuthorizationState?> = telegramClient.authState

    val isAuthorized: Flow<Boolean> = telegramClient.authState.map { state ->
        state is TdApi.AuthorizationStateReady
    }

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        secureSettings.saveApiCredentials(apiId, apiHash)
        telegramClient.setApiCredentials(apiId, apiHash)
    }

    fun logout() {
        secureSettings.saveBoolean("active_provider_google", false)
        secureSettings.saveBoolean("active_provider_telegram", false)
        telegramClient.send(TdApi.LogOut())
    }

    fun logoutAll() {
        logout()
    }
}
