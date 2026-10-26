package com.awd.teledrive.data.repository

import android.content.Context
import android.util.Log
import com.awd.teledrive.data.model.GitHubRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class NewVersionAvailable(val release: GitHubRelease) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

@Singleton
class UpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    private var hasDismissedUpdate = false

    // আপনার গিটহাব রিপোজিটরি সরাসরি যুক্ত করা হলো
    private val GITHUB_REPO = "Yasin-Os/teledrive"

    private val prefs by lazy {
        context.getSharedPreferences("app_auto_update_prefs", Context.MODE_PRIVATE)
    }

    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    suspend fun checkForUpdates(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (_updateState.value is UpdateState.Checking) return@withContext
        if (!manual && hasDismissedUpdate) return@withContext
        
        _updateState.value = UpdateState.Checking
        
        try {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                val latestRelease = json.decodeFromString<GitHubRelease>(responseBody)
                
                val lastKnownTag = prefs.getString("last_installed_tag", null)

                if (lastKnownTag == null) {
                    prefs.edit().putString("last_installed_tag", latestRelease.tag_name).apply()
                    if (manual) {
                        _updateState.value = UpdateState.NewVersionAvailable(latestRelease)
                    } else {
                        _updateState.value = UpdateState.UpToDate
                    }
                } else if (latestRelease.tag_name != lastKnownTag || manual) {
                    _updateState.value = UpdateState.NewVersionAvailable(latestRelease)
                } else {
                    _updateState.value = UpdateState.UpToDate
                }
            } else {
                _updateState.value = UpdateState.Error("HTTP Error: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("UpdateRepository", "Failed to check for updates", e)
            _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
        } finally {
            if (!manual && (_updateState.value is UpdateState.UpToDate || _updateState.value is UpdateState.Error)) {
                _updateState.value = UpdateState.Idle
            }
        }
    }

    fun resetState() {
        val currentState = _updateState.value
        if (currentState is UpdateState.NewVersionAvailable) {
            hasDismissedUpdate = true
            prefs.edit().putString("last_installed_tag", currentState.release.tag_name).apply()
        }
        _updateState.value = UpdateState.Idle
    }
}
