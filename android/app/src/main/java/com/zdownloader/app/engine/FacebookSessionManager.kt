package com.zdownloader.app.engine

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class FacebookSessionStatus {
    NOT_CONNECTED,
    CONNECTED,
    SESSION_EXPIRED
}

object FacebookSessionManager {
    @Volatile
    private var sessionExpired: Boolean = false

    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<FacebookSessionStatus> = _status.asStateFlow()

    private fun computeStatus(): FacebookSessionStatus {
        if (sessionExpired) return FacebookSessionStatus.SESSION_EXPIRED
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return FacebookSessionStatus.NOT_CONNECTED
        if (!hasAuthCookies(cm)) return FacebookSessionStatus.NOT_CONNECTED
        return FacebookSessionStatus.CONNECTED
    }

    @JvmStatic
    fun getFacebookCookies(cm: CookieManager? = runCatching { CookieManager.getInstance() }.getOrNull()): String {
        if (cm == null) return ""
        val urls = listOf("https://www.facebook.com", "https://m.facebook.com", "https://facebook.com")
        return urls.mapNotNull { runCatching { cm.getCookie(it) }.getOrNull() }
            .filter { it.isNotBlank() }
            .joinToString(";")
    }

    @JvmStatic
    fun hasAuthCookies(cm: CookieManager? = runCatching { CookieManager.getInstance() }.getOrNull()): Boolean {
        if (cm == null) return false
        val cookieStr = getFacebookCookies(cm)
        if (cookieStr.isBlank()) return false
        val parts = cookieStr.split(";")
        val hasCUser = parts.any { part ->
            val trimmed = part.trim()
            trimmed.startsWith("c_user=") && trimmed.substringAfter("c_user=").isNotBlank()
        }
        val hasXs = parts.any { part ->
            val trimmed = part.trim()
            trimmed.startsWith("xs=") && trimmed.substringAfter("xs=").isNotBlank()
        }
        return hasCUser && hasXs
    }

    @JvmStatic
    fun checkStatus(): FacebookSessionStatus {
        val s = computeStatus()
        _status.value = s
        return s
    }

    @JvmStatic
    fun hasActiveSession(): Boolean {
        return computeStatus() == FacebookSessionStatus.CONNECTED
    }

    @JvmStatic
    fun isSessionExpired(): Boolean {
        return computeStatus() == FacebookSessionStatus.SESSION_EXPIRED
    }

    @JvmStatic
    fun markSessionValid() {
        sessionExpired = false
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        cm?.flush()
        _status.value = computeStatus()
    }

    @JvmStatic
    fun markSessionExpired() {
        sessionExpired = true
        _status.value = FacebookSessionStatus.SESSION_EXPIRED
    }

    @JvmStatic
    fun logout(context: Context? = null) {
        sessionExpired = false
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        if (cm != null) {
            val domains = listOf(".facebook.com", "facebook.com", "m.facebook.com", "www.facebook.com")
            val urls = listOf("https://www.facebook.com", "https://m.facebook.com", "https://facebook.com")
            for (url in urls) {
                val cookieStr = cm.getCookie(url) ?: continue
                for (part in cookieStr.split(";")) {
                    val name = part.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        for (domain in domains) {
                            cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Domain=$domain; Path=/; Secure; HttpOnly")
                            cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Domain=$domain; Path=/")
                        }
                        cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Secure; HttpOnly")
                        cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
                    }
                }
            }
            cm.flush()
        }
        if (context != null) {
            runCatching {
                val legacy = File(context.filesDir, "facebook_session.json")
                if (legacy.exists()) legacy.delete()
            }
        }
        _status.value = FacebookSessionStatus.NOT_CONNECTED
    }
}
