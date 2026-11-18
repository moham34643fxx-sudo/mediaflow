package com.zdownloader.app.domain

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Unwrap known sharing envelopes only at input, never rewrite signed transport URLs. */
object SharedLinks {
    fun normalize(input:String):String {
        var current=UrlPolicy.normalize(input)
        repeat(5) {
            val uri=URI(current)
            val host=uri.host.lowercase(Locale.ROOT)
            val keys=when {
                host in setOf("l.facebook.com","lm.facebook.com","www.facebook.com","facebook.com")&&uri.path=="/l.php"->listOf("u")
                host=="l.instagram.com"&&uri.path in setOf("","/")->listOf("u")
                host in setOf("youtube.com","www.youtube.com","m.youtube.com")&&uri.path=="/redirect"->listOf("q","url")
                host in setOf("youtube.com","www.youtube.com","m.youtube.com")&&uri.path=="/attribution_link"->listOf("u")
                host.removePrefix("www.") in setOf("google.com","google.co.uk","google.com.eg","google.de","google.fr","google.ca","google.com.au")&&uri.path=="/url"->listOf("url","q")
                host.endsWith(".safelinks.protection.outlook.com")->listOf("url")
                else->return current
            }
            val parameters=uri.rawQuery.orEmpty().split('&')
            val encoded=keys.firstNotNullOfOrNull {key->parameters.firstOrNull {it.substringBefore('=')==key}?.substringAfter('=',"")}
                ?.takeIf {it.isNotBlank()} ?: return current
            val decoded=runCatching {
                if(encoded.startsWith("https://",ignoreCase=true)) encoded else URLDecoder.decode(encoded,"UTF-8")
            }.getOrElse {throw MediaException(ErrorCode.INVALID_URL)}
            val destination=if(uri.path=="/attribution_link"&&decoded.startsWith('/')&&!decoded.startsWith("//")) uri.resolve(decoded).toString() else decoded
            current=UrlPolicy.normalize(destination)
        }
        return current
    }
}
