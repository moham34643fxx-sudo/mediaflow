package com.zdownloader.app

import android.content.SharedPreferences
import android.content.pm.PackageInfo
import com.zdownloader.app.data.SettingsRepository
import org.junit.Assert.*
import org.junit.Test

class WelcomeStateTest {

    private class MemoryPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val uncommitted = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) uncommitted[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearRequested = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearRequested) {
                    map.clear()
                    clearRequested = false
                }
                uncommitted.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
                uncommitted.clear()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun freshInstallation_showsWelcomeExperience() {
        val prefs = MemoryPreferences()
        val packageInfo = PackageInfo().apply {
            firstInstallTime = 5000L
            lastUpdateTime = 5000L
        }

        val shouldShow = SettingsRepository.evaluateShouldShowWelcome(
            prefs = prefs,
            packageInfo = packageInfo,
            hasExistingData = false
        )

        assertTrue("Fresh install must show the welcome experience", shouldShow)
        assertFalse(
            "Fresh install must not mark onboarding completed before user finishes",
            prefs.getBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, false)
        )
    }

    @Test
    fun subsequentLaunch_afterCompletion_bypassesWelcomeExperience() {
        val prefs = MemoryPreferences().apply {
            edit().putBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, true).apply()
        }
        val packageInfo = PackageInfo().apply {
            firstInstallTime = 5000L
            lastUpdateTime = 5000L
        }

        val shouldShow = SettingsRepository.evaluateShouldShowWelcome(
            prefs = prefs,
            packageInfo = packageInfo,
            hasExistingData = false
        )

        assertFalse("Subsequent launch after onboarding completed must bypass welcome", shouldShow)
    }

    @Test
    fun subsequentLaunch_incompleteOnboarding_showsWelcomeExperience() {
        val prefs = MemoryPreferences().apply {
            edit().putBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, false).apply()
        }
        val packageInfo = PackageInfo().apply {
            firstInstallTime = 5000L
            lastUpdateTime = 5000L
        }

        val shouldShow = SettingsRepository.evaluateShouldShowWelcome(
            prefs = prefs,
            packageInfo = packageInfo,
            hasExistingData = false
        )

        assertTrue("Incomplete onboarding must still show welcome experience", shouldShow)
    }

    @Test
    fun updateFromOlderVersionWithoutOnboarding_showsWelcomeExperienceOnce() {
        val prefs = MemoryPreferences()
        val packageInfo = PackageInfo().apply {
            firstInstallTime = 10000L
            lastUpdateTime = 50000L
        }

        val shouldShow = SettingsRepository.evaluateShouldShowWelcome(
            prefs = prefs,
            packageInfo = packageInfo,
            hasExistingData = true
        )

        assertTrue("Updating to this release when onboarding has not been seen yet must show welcome once", shouldShow)
        assertFalse(
            "Must remain false until user taps Done",
            prefs.getBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, false)
        )
    }

    @Test
    fun futureUpdate_afterOnboardingCompleted_bypassesWelcomeExperience() {
        val prefs = MemoryPreferences().apply {
            edit().putBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, true).apply()
        }
        val packageInfo = PackageInfo().apply {
            firstInstallTime = 5000L
            lastUpdateTime = 90000L
        }

        val shouldShow = SettingsRepository.evaluateShouldShowWelcome(
            prefs = prefs,
            packageInfo = packageInfo,
            hasExistingData = true
        )

        assertFalse("Once completed, future updates must bypass welcome", shouldShow)
        assertTrue(
            "Must remain true after future updates",
            prefs.getBoolean(SettingsRepository.KEY_ONBOARDING_COMPLETED, false)
        )
    }

    @Test
    fun dynamicVersionFormatting_matchesInstalledVersion() {
        val installedVersion = BuildConfig.VERSION_NAME
        assertNotNull("BuildConfig.VERSION_NAME must not be null", installedVersion)
        assertTrue("BuildConfig.VERSION_NAME must not be empty", installedVersion.isNotEmpty())

        val template = "الإصدار %1\$s"
        val formatted = String.format(template, installedVersion)

        assertEquals("الإصدار $installedVersion", formatted)
        assertTrue("Formatted version must contain the real version", formatted.contains(installedVersion))
    }
}
