package com.zdownloader.app.ui

import com.zdownloader.app.engine.FacebookSessionManager
import java.net.URI
import java.util.Locale

internal enum class FacebookAuthOriginKind {
    FACEBOOK_HTTPS,
    EXTERNAL_HTTPS,
    BLOCKED_HTTP,
    BLOCKED_UNVERIFIED
}

internal data class FacebookAuthOrigin(
    val host:String?,
    val kind:FacebookAuthOriginKind
)

/**
 * Auth mode keeps HTTPS identity-provider navigation usable, but makes its origin visible.
 * Facebook session completion uses the same exact HTTPS host policy as the scraper cookie jar.
 */
internal object FacebookAuthNavigationPolicy {
    private const val MAX_URL_LENGTH=8192

    fun inspect(url:String):FacebookAuthOrigin {
        if(url.length>MAX_URL_LENGTH) return FacebookAuthOrigin(null,FacebookAuthOriginKind.BLOCKED_UNVERIFIED)
        val uri=runCatching {URI(url)}.getOrNull()
            ?: return FacebookAuthOrigin(null,FacebookAuthOriginKind.BLOCKED_UNVERIFIED)
        val host=uri.host?.lowercase(Locale.ROOT)
        if(host.isNullOrBlank()||uri.userInfo!=null||uri.rawAuthority.orEmpty().endsWith(':'))
            return FacebookAuthOrigin(host,FacebookAuthOriginKind.BLOCKED_UNVERIFIED)

        val scheme=uri.scheme?.lowercase(Locale.ROOT)
        if(scheme=="http"&&(uri.port==-1||uri.port==80))
            return FacebookAuthOrigin(host,FacebookAuthOriginKind.BLOCKED_HTTP)
        if(scheme!="https"||uri.port !in setOf(-1,443))
            return FacebookAuthOrigin(host,FacebookAuthOriginKind.BLOCKED_UNVERIFIED)

        return if(FacebookSessionManager.isFacebookCookieUrl(url))
            FacebookAuthOrigin(host,FacebookAuthOriginKind.FACEBOOK_HTTPS)
        else FacebookAuthOrigin(host,FacebookAuthOriginKind.EXTERNAL_HTTPS)
    }

    fun allowsAuthNavigation(url:String):Boolean = when(inspect(url).kind) {
        FacebookAuthOriginKind.FACEBOOK_HTTPS,FacebookAuthOriginKind.EXTERNAL_HTTPS->true
        FacebookAuthOriginKind.BLOCKED_HTTP,FacebookAuthOriginKind.BLOCKED_UNVERIFIED->false
    }

    fun isFacebookAuthOrigin(url:String):Boolean =
        inspect(url).kind==FacebookAuthOriginKind.FACEBOOK_HTTPS
}
