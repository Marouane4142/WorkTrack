package fr.worktrack.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.worktrack.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AppSettings(val rules: WorkRules = WorkRules(), val preferences: Preferences = Preferences(),
    val profile: Profile = Profile(), val legacyImported: Boolean = false)

@Singleton
class SettingsStore @Inject constructor(private val store: DataStore<androidx.datastore.preferences.core.Preferences>) {
    private val key = stringPreferencesKey("settings_v2")
    val settings = store.data.map { values -> values[key]?.let { EntryCodec.json.decodeFromString<AppSettings>(it) } ?: AppSettings() }
    suspend fun current() = settings.first()
    suspend fun save(value: AppSettings) { value.rules.validate(); store.edit { it[key] = EntryCodec.json.encodeToString(value) } }
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { values ->
            val original = values[key]?.let { EntryCodec.json.decodeFromString<AppSettings>(it) } ?: AppSettings()
            val updated = transform(original)
            updated.rules.validate()
            values[key] = EntryCodec.json.encodeToString(updated)
        }
    }
}
