package com.szc.learning.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

/**
 * 会话本地存储：token + 少量用户摘要。
 *
 * 除了落盘，还在内存里缓存一份 token，因为 [AuthInterceptor] 是同步执行的，
 * 拿不到 suspend 的 DataStore 读取。缓存值在登录、登出和冷启动时刷新。
 */
class SessionStore(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.authDataStore

    @Volatile
    private var memoryToken: String? = null

    val tokenFlow: Flow<String?> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[KEY_TOKEN] }

    val nicknameFlow: Flow<String> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[KEY_NICKNAME].orEmpty() }

    /** 同步读取 token，供拦截器使用。可能为 null（尚未登录或缓存未加载）。 */
    fun token(): String? = memoryToken

    /** 冷启动时调用一次，把落盘的 token 灌进内存缓存。 */
    suspend fun restore(): String? {
        val stored = tokenFlow.first()
        memoryToken = stored
        return stored
    }

    suspend fun save(token: String, user: UserInfo?, expiresInSeconds: Long?) {
        memoryToken = token
        val expiresAt = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + expiresInSeconds * 1000
        } else {
            0L
        }
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_USER_ID] = user?.userId ?: 0L
            prefs[KEY_USERNAME] = user?.userName.orEmpty()
            prefs[KEY_NICKNAME] = user?.displayName.orEmpty()
            prefs[KEY_AVATAR] = user?.avatar.orEmpty()
            prefs[KEY_EXPIRES_AT] = expiresAt
        }
    }

    suspend fun updateUser(user: UserInfo) {
        dataStore.edit { prefs ->
            prefs[KEY_USER_ID] = user.userId
            prefs[KEY_USERNAME] = user.userName
            prefs[KEY_NICKNAME] = user.displayName
            prefs[KEY_AVATAR] = user.avatar.orEmpty()
        }
    }

    suspend fun clear() {
        memoryToken = null
        dataStore.edit { prefs -> prefs.clear() }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_USER_ID = longPreferencesKey("user_id")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_NICKNAME = stringPreferencesKey("nickname")
        val KEY_AVATAR = stringPreferencesKey("avatar")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
    }
}
