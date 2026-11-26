package io.github.absolutezero000.deckhand.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class SettingsState(
    val repo: String?,
    val hasToken: Boolean,
    val demo: Boolean,
    val contentVersion: String?,
    val lastSyncMillis: Long,
    val lastError: String?,
    val newPerDay: Int,
    val theme: ThemeMode,
) {
    val configured get() = demo || (repo != null && hasToken)
}

class Settings(private val context: Context) {
    private object K {
        val repo = stringPreferencesKey("repo")
        val token = stringPreferencesKey("token_enc")
        val etag = stringPreferencesKey("etag")
        val version = stringPreferencesKey("content_version")
        val lastSync = longPreferencesKey("last_sync_millis")
        val lastError = stringPreferencesKey("last_sync_error")
        val newPerDay = intPreferencesKey("new_per_day")
        val demo = booleanPreferencesKey("demo")
        val theme = stringPreferencesKey("theme")
    }

    val state: Flow<SettingsState> = context.store.data.map { it.toState() }

    private fun Preferences.toState() = SettingsState(
        repo = this[K.repo], hasToken = this[K.token] != null, demo = this[K.demo] ?: false,
        contentVersion = this[K.version], lastSyncMillis = this[K.lastSync] ?: 0,
        lastError = this[K.lastError], newPerDay = this[K.newPerDay] ?: DEFAULT_NEW_PER_DAY,
        theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
    )

    suspend fun setTheme(mode: ThemeMode) = context.store.edit { it[K.theme] = mode.name }

    suspend fun current(): SettingsState = state.first()

    suspend fun credentials(): Pair<String, String>? {
        val p = context.store.data.first()
        val repo = p[K.repo] ?: return null
        val token = p[K.token]?.let(TokenCipher::decrypt) ?: return null
        return repo to token
    }

    suspend fun etag(): String? = context.store.data.first()[K.etag]

    /** Saves a verified connection and its first sync result. */
    suspend fun saveConnection(repo: String, token: String, version: String, etag: String?, nowMillis: Long) {
        val enc = TokenCipher.encrypt(token)
        context.store.edit {
            it[K.repo] = repo
            it[K.token] = enc
            it[K.demo] = false
            it[K.version] = version
            if (etag != null) it[K.etag] = etag else it.remove(K.etag)
            it[K.lastSync] = nowMillis
            it.remove(K.lastError)
        }
    }

    suspend fun setDemo(version: String) = context.store.edit {
        it.remove(K.repo); it.remove(K.token); it.remove(K.etag); it.remove(K.lastError)
        it[K.demo] = true
        it[K.version] = version
        it[K.lastSync] = 0
    }

    suspend fun recordSync(version: String?, etag: String?, nowMillis: Long) = context.store.edit {
        if (version != null) it[K.version] = version
        if (etag != null) it[K.etag] = etag
        it[K.lastSync] = nowMillis
        it.remove(K.lastError)
    }

    suspend fun recordError(message: String) = context.store.edit { it[K.lastError] = message }

    suspend fun setNewPerDay(n: Int) = context.store.edit { it[K.newPerDay] = n.coerceIn(0, 200) }

    /** Forgets the repo, token and sync state but keeps preferences like the daily limit. */
    suspend fun clearConnection() = context.store.edit {
        it.remove(K.repo); it.remove(K.token); it.remove(K.etag); it.remove(K.version)
        it.remove(K.lastSync); it.remove(K.lastError); it.remove(K.demo)
    }

    companion object {
        const val DEFAULT_NEW_PER_DAY = 20
    }
}
