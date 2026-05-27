package `fun`.kirari.hanako.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "hanako_settings")

class SettingsStore(private val context: Context) {
    private val tag = "HanakoSettingsStore"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val raw = preferences[SETTINGS_KEY]
        val appSettings = if (raw.isNullOrBlank()) {
            AppSettings().normalize()
        } else {
            runCatching { json.decodeFromString<AppSettings>(raw).normalize() }.getOrElse { AppSettings().normalize() }
        }
        migrateHistoryImages(context, appSettings)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { preferences ->
            val currentRaw = preferences[SETTINGS_KEY]
            val current = if (currentRaw.isNullOrBlank()) {
                AppSettings().normalize()
            } else {
                runCatching { json.decodeFromString<AppSettings>(currentRaw).normalize() }.getOrElse { AppSettings().normalize() }
            }
            val updated = transform(current).normalize()
            AppDebugLogStore.i(
                tag,
                "update lastResultId=${updated.lastResult?.id} historySize=${updated.history.size} latestHistoryId=${updated.history.firstOrNull()?.id}"
            )
            preferences[SETTINGS_KEY] = json.encodeToString(AppSettings.serializer(), updated)
        }
    }

    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("app_settings")

        private suspend fun migrateHistoryImages(context: Context, settings: AppSettings): AppSettings {
            val needsMigration = settings.history.any { it.screenshotBase64 != null && it.screenshotPath == null }
            if (!needsMigration) return settings

            val migratedHistory = settings.history.map { result ->
                migrateBase64ToFile(context, result)
            }
            val migratedLastResult = settings.lastResult?.let { migrateBase64ToFile(context, it) }
            return settings.copy(history = migratedHistory, lastResult = migratedLastResult)
        }
    }
}
