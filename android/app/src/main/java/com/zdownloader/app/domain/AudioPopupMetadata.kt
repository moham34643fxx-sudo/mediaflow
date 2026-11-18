package com.zdownloader.app.domain

/** Provider/embedded labels are display text, never a reason to expose a local URI. */
object AudioPopupMetadata {
    private val technical=Regex("(?:content|file|https?)://",RegexOption.IGNORE_CASE)
    private val drive=Regex("^[a-zA-Z]:[\\\\/]")
    fun label(candidate:String?):String? {
        val label=candidate?.filterNot {it.isISOControl()}?.trim()?.take(512) ?: return null
        return label.takeUnless {it.isEmpty()||it=="<unknown>"||technical.containsMatchIn(it)||it.startsWith('/')||drive.containsMatchIn(it)}
    }
}
