package com.siroha.flashtool.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

private val Context.themeDataStore by preferencesDataStore(name = "siroha_theme_prefs")

/**
 * Persists the user's theme choice: System / Light / Dark / AMOLED (pure
 * black), plus whether Material You dynamic color is enabled. Read by
 * [com.siroha.flashtool.ui.theme.SirohaFlashToolTheme], written from the
 * Settings screen.
 */
class ThemePreferences(private val context: Context) {
    companion object {
        private val KEY_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[KEY_MODE] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColorEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }
}
