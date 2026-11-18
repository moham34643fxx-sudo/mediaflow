package com.zdownloader.app.domain

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal enum class ReportProblem(val label:String) {
    UNSUPPORTED("Unsupported URL"), ANALYSIS("Link analysis failed"), DOWNLOAD("Download failed"),
    MEDIA("Audio or video problem"), OTHER("Other problem")
}

/** Reports contain reviewed user input and a public error code, never logs or device identifiers. */
internal data class ProblemReport(
    val url:String="",val problem:ReportProblem=ReportProblem.OTHER,val description:String="",val errorCode:String=""
) {
    fun validationError():String? = when {
        url.length>4000||description.length>2000->"too_long"
        url.isBlank()&&description.trim().length<10->"empty"
        url.isNotBlank()&&safeReportUrl(url)==null->"url"
        else->null
    }

    fun message(version:String):String {
        require(validationError()==null)
        require(version.matches(Regex("[A-Za-z0-9.+_-]{1,100}")))
        val details=errorCode.takeIf {it.matches(Regex("[A-Z][A-Z0-9_]{0,79}"))}.orEmpty()
        return listOf("MediaFlow — Problem Report","Problem Type: ${problem.label}",
            "Media URL: ${safeReportUrl(url).orEmpty()}","Description: ${description.trim()}",
            "App Version: $version","Error Details: $details").joinToString("\n\n")
    }

    fun whatsAppUrl(version:String):String = "https://wa.me/201099305871?text="+
        URLEncoder.encode(message(version),"UTF-8").replace("+","%20")
}

private val sensitiveReportParameter=Regex("(?i)(?:.*(?:token|secret|password|signature|credential|authorization|session|cookie).*|key|sig|auth|policy|x-amz-.*|x-goog-.*)")

internal fun safeReportUrl(input:String):String? {
    if(input.isBlank()) return ""
    return try {
        val uri=URI(input.trim())
        if(uri.scheme?.lowercase(Locale.ROOT) !in setOf("http","https")||uri.host.isNullOrBlank()) return null
        val query=uri.rawQuery?.split('&')?.filterNot {part->
            sensitiveReportParameter.matches(URLDecoder.decode(part.substringBefore('='),"UTF-8"))
        }?.joinToString("&")?.takeIf {it.isNotBlank()}
        buildString {
            append(uri.scheme.lowercase(Locale.ROOT));append("://")
            append(uri.rawAuthority.substringAfterLast('@'))
            append(uri.rawPath.orEmpty())
            if(query!=null) {append('?');append(query)}
        }
    } catch(_:URISyntaxException) {null} catch(_:IllegalArgumentException) {null}
}
