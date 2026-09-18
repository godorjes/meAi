package app.polyglot.dev.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "polyglot_prefs")

/** 轻量界面状态：上次会话等 */
class Prefs(private val context: Context) {

    private val keyLastSessionId = stringPreferencesKey("last_session_id")
    private val keyCatalogVersion = stringPreferencesKey("catalog_version")

    val lastSessionId: Flow<String?> = context.dataStore.data.map { it[keyLastSessionId] }

    suspend fun setLastSessionId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(keyLastSessionId) else prefs[keyLastSessionId] = id
        }
    }

    /** 已生效的公共目录版本：随包内置目录版本更高时才替换，避免每次启动都重写 */
    val catalogVersion: Flow<Long> = context.dataStore.data.map {
        it[keyCatalogVersion]?.toLongOrNull() ?: 0L
    }

    suspend fun setCatalogVersion(version: Long) {
        context.dataStore.edit { it[keyCatalogVersion] = version.toString() }
    }
}
