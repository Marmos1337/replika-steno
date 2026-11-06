package ru.replika.steno.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val llmKey = booleanPreferencesKey("llm_enabled")

    val theme: Flow<ThemeMode> = context.dataStore.data.map { p ->
        p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }
    val llmEnabled: Flow<Boolean> = context.dataStore.data.map { it[llmKey] ?: true }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setLlmEnabled(on: Boolean) = context.dataStore.edit { it[llmKey] = on }

    /**
     * Synchronous mirror of the theme in SharedPreferences, read in Activity.onCreate before the first
     * frame so the window starts in the right colors (no white flash when the app is set to dark).
     */
    fun themeSync(): ThemeMode = context.getSharedPreferences("theme_mirror", Context.MODE_PRIVATE)
        .getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM

    fun mirrorTheme(mode: ThemeMode) {
        context.getSharedPreferences("theme_mirror", Context.MODE_PRIVATE).edit().putString("theme", mode.name).apply()
    }
}
