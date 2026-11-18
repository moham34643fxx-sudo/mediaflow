package com.zdownloader.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("settings")
data class Settings(val language: String = "ar", val theme: String = "dark", val concurrency: Int = 2, val wifiOnly: Boolean = false, val destination: String? = null, val retries: Int = 2, val template: String = "{title} - MediaFlow", val notifications: Boolean = true, val preferredHeight: Int = 0, val downloadType: String = "ask", val completionNotifications: Boolean = true, val failureNotifications: Boolean = true,val usageStatistics:Boolean=false,val cloudNotices:Boolean=false)
@Singleton class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val flow = context.preferences.data.map { p -> Settings(p[stringPreferencesKey("language")] ?: "ar", p[stringPreferencesKey("theme")] ?: "dark", (p[intPreferencesKey("concurrency")] ?: 2).coerceIn(0,5), p[booleanPreferencesKey("wifi")] ?: false, p[stringPreferencesKey("destination")]?.takeIf { it.isNotBlank() }, (p[intPreferencesKey("retries")] ?: 2).coerceIn(0,5), p[stringPreferencesKey("template")] ?: "{title} - MediaFlow", p[booleanPreferencesKey("notifications")] ?: true, (p[intPreferencesKey("preferred_height")] ?: 0).takeIf { it in listOf(0,1080,720,480,360) } ?: 0, (p[stringPreferencesKey("download_type")] ?: "ask").takeIf { it in listOf("ask","video","audio") } ?: "ask", p[booleanPreferencesKey("completion_notifications")] ?: (p[booleanPreferencesKey("notifications")] ?: true), p[booleanPreferencesKey("failure_notifications")] ?: (p[booleanPreferencesKey("notifications")] ?: true),p[booleanPreferencesKey("usage_statistics")] ?: false,p[booleanPreferencesKey("cloud_notices")] ?: false) }
    suspend fun current() = flow.first()
    suspend fun text(key: String, value: String) { context.preferences.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun integer(key: String, value: Int) { context.preferences.edit { it[intPreferencesKey(key)] = value } }
    suspend fun boolean(key: String, value: Boolean) { context.preferences.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun notification(key:String,value:Boolean) {
        require(key in setOf("completion_notifications","failure_notifications"))
        context.preferences.edit {prefs->
            if(value&&prefs[booleanPreferencesKey("notifications")]==false) prefs[booleanPreferencesKey(if(key=="completion_notifications") "failure_notifications" else "completion_notifications")]=false
            prefs[booleanPreferencesKey(key)]=value
            if(value) prefs[booleanPreferencesKey("notifications")]=true
        }
    }

    companion object {
        const val PREFS_ONBOARDING = "mediaflow_onboarding"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        val ONBOARDING_DATASTORE_KEY = booleanPreferencesKey(KEY_ONBOARDING_COMPLETED)

        fun shouldShowWelcome(context: Context): Boolean {
            val prefs = runCatching {
                context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
            }.getOrNull() ?: return false

            return evaluateShouldShowWelcome(prefs)
        }

        fun evaluateShouldShowWelcome(
            prefs: SharedPreferences?,
            @Suppress("UNUSED_PARAMETER") packageInfo: PackageInfo? = null,
            @Suppress("UNUSED_PARAMETER") hasExistingData: Boolean = false
        ): Boolean {
            if (prefs == null) return false

            if (prefs.contains(KEY_ONBOARDING_COMPLETED)) {
                return !prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
            }

            runCatching {
                prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, false).apply()
            }
            return true
        }
    }

    fun shouldShowWelcome(): Boolean = shouldShowWelcome(context)

    suspend fun completeOnboarding() {
        runCatching {
            context.getSharedPreferences(PREFS_ONBOARDING, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
        }
        context.preferences.edit { it[ONBOARDING_DATASTORE_KEY] = true }
    }
}
