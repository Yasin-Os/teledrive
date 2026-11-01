package com.awd.teledrive.data.remote

import android.content.Context
import android.content.Intent
import com.awd.teledrive.data.secure.SecureSettings
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secureSettings: SecureSettings
) {
    // আপনার তৈরি করা Google Client ID
    private val webClientId = "23160235847-23ue78ne5keckhjgj82p7mmm38ktqqt1.apps.googleusercontent.com"

    private val _isGoogleSignedIn = MutableStateFlow(
        GoogleSignIn.getLastSignedInAccount(context) != null
    )
    val isGoogleSignedIn = _isGoogleSignedIn.asStateFlow()

    private val _googleAuthError = MutableStateFlow<String?>(null)
    val googleAuthError = _googleAuthError.asStateFlow()

    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_APPDATA))
            .requestIdToken(webClientId)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun handleSignInResult(intent: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            val account = task.result
            if (account != null) {
                _isGoogleSignedIn.value = true
                secureSettings.saveBoolean("active_provider_google", true)
                secureSettings.saveBoolean("active_provider_telegram", false)
            } else {
                _googleAuthError.value = "Sign in failed: Account is null"
            }
        } catch (e: Exception) {
            _googleAuthError.value = "Sign in failed: ${e.message}"
        }
    }

    fun signOut(onComplete: () -> Unit) {
        getSignInClient().signOut().addOnCompleteListener {
            _isGoogleSignedIn.value = false
            secureSettings.saveBoolean("active_provider_google", false)
            onComplete()
        }
    }

    fun clearError() {
        _googleAuthError.value = null
    }
}
