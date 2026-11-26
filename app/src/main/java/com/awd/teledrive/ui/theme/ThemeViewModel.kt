package com.awd.teledrive.ui.theme

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import com.awd.teledrive.data.secure.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H12: single process-wide source of truth for Dark Theme and Accent Color.
 * MainActivity (Activity-scoped ThemeViewModel) and SettingsScreen
 * (NavBackStackEntry-scoped ThemeViewModel) both observe these StateFlows,
 * so theme changes apply live without restarting the app.
 */
@Singleton
class ThemeRepository @Inject constructor(
    private val secureSettings: SecureSettings,
) {
    companion object {
        private const val TAG = "ThemeRepository"
        const val KEY_THEME_MODE_KEY = "theme_mode_key"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_COLOR = "theme_color"
        const val MODE_SYSTEM = "system"
        const val MODE_SET = "set"
        const val DEFAULT_THEME_COLOR_ARGB = 0xFF24A1DE.toInt()

        /**
         * Resolves the persisted dark-mode preference. Returns `null` ("follow
         * system") both on a fresh install (`modeKey == null`) and when the
         * user explicitly selected system mode (`modeKey == "system"`).
         */
        internal fun resolveDarkModePreference(modeKey: String?, darkModeFlag: Boolean): Boolean? {
            return when (modeKey) {
                null, MODE_SYSTEM -> null
                else -> darkModeFlag
            }
        }
    }

    private val _isDarkMode = MutableStateFlow(readDarkModeFromStorage())
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    private val _themeColor = MutableStateFlow(readThemeColorFromStorage())
    val themeColor: StateFlow<Color> = _themeColor.asStateFlow()

    private fun readDarkModeFromStorage(): Boolean? {
        return resolveDarkModePreference(
            modeKey = secureSettings.getString(KEY_THEME_MODE_KEY),
            darkModeFlag = secureSettings.getBoolean(KEY_THEME_MODE)
        )
    }

    private fun readThemeColorFromStorage(): Color {
        return Color(secureSettings.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR_ARGB))
    }

    /** Re-reads saved theme preferences after SecureSettings recovers from a startup failure. */
    fun reloadFromStorage() {
        _isDarkMode.value = readDarkModeFromStorage()
        _themeColor.value = readThemeColorFromStorage()
    }

    fun setDarkMode(enabled: Boolean?) {
        _isDarkMode.value = enabled
        try {
            if (enabled == null) {
                secureSettings.saveString(KEY_THEME_MODE_KEY, MODE_SYSTEM)
            } else {
                secureSettings.saveString(KEY_THEME_MODE_KEY, MODE_SET)
                secureSettings.saveBoolean(KEY_THEME_MODE, enabled)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not persist dark mode preference: secure storage unavailable", e)
        }
    }

    fun setThemeColor(color: Color) {
        _themeColor.value = color
        try {
            secureSettings.saveInt(KEY_THEME_COLOR, color.toArgb())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not persist theme color preference: secure storage unavailable", e)
        }
    }
}

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeRepository: ThemeRepository,
) : ViewModel() {
    val isDarkMode: StateFlow<Boolean?> = themeRepository.isDarkMode
    val themeColor: StateFlow<Color> = themeRepository.themeColor

    fun setDarkMode(enabled: Boolean?) {
        themeRepository.setDarkMode(enabled)
    }

    fun setThemeColor(color: Color) {
        themeRepository.setThemeColor(color)
    }
}
