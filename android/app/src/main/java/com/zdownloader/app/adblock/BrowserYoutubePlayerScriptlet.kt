package com.zdownloader.app.adblock

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.IOException

/** The APK-bundled Brave output is independent of downloaded filters and their readiness. */
internal object BrowserYoutubePlayerScriptlet {
    private const val ASSET = "adblock/youtube-player-document-start.js"
    private const val MAX_ASSET_BYTES = 16 * 1024
    private val hostsByOrigin = mapOf(
        "https://youtube.com" to "youtube.com",
        "https://www.youtube.com" to "www.youtube.com",
        "https://m.youtube.com" to "m.youtube.com"
    )

    fun isAllowed(origin: String?, settings: BrowserAdblockState): Boolean {
        val host = hostsByOrigin[origin] ?: return false
        return settings.enabled && host !in settings.excludedHosts
    }

    /** Called off the UI thread. An unavailable resource must not prevent browsing. */
    fun load(context: Context): String {
        return try {
            context.assets.open(ASSET).use { input ->
                val bytes = ByteArray(MAX_ASSET_BYTES + 1)
                var length = 0
                while (length < bytes.size) {
                    val read = input.read(bytes, length, bytes.size - length)
                    if (read < 0) break
                    if (read == 0) return ""
                    length += read
                }
                if (length in 1..MAX_ASSET_BYTES) {
                    bytes.decodeToString(endIndex = length, throwOnInvalidSequence = true)
                } else ""
            }
        } catch (_: IOException) {
            ""
        }
    }

    fun register(view: WebView, source: String, gateKey: String) {
        // No fallback to onPageStarted: executing after the site's first script
        // is not equivalent. This WebKit version injects into the page world.
        val registration = try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                "unsupported"
            } else {
                WebViewCompat.addDocumentStartJavaScript(view, guardedScript(source, gateKey), hostsByOrigin.keys)
                "registered"
            }
        } catch (_: RuntimeException) {
            "registration_failed"
        } catch (_: LinkageError) {
            "registration_failed"
        }
        Log.d("MediaFlowAdblockQA", "event=scriptlet_setup document_start=$registration")
    }

    private fun guardedScript(source: String, gateKey: String): String = """
        (() => {
          const hostBridge = window.${BrowserCosmeticBridge.NAME};
          const origin = location.origin;
          const mfAllowsYoutubePlayerScriptlet = () =>
            hostBridge?.youtubePlayerScriptletAllowed('$gateKey', origin) === true;
          $source
        })();
    """.trimIndent()
}
