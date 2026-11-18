package com.zdownloader.app.domain

import java.security.MessageDigest

data class ClipboardLink(val url:String,val fingerprint:String)

/** A copied-link offer is not a download request. Persist only its opaque fingerprint. */
class ClipboardLinkPrompt(lastHandledFingerprint:String?=null) {
    var lastHandledFingerprint:String?=lastHandledFingerprint
        private set
    var pending:ClipboardLink?=null
        private set

    fun offer(text:String?,copiedAt:Long,alreadyHandling:String?=null) {
        val url=text?.takeIf {it.length<=UrlPolicy.MAX_INPUT_LENGTH}?.let {QuickShareFormats.sharedUrl(listOf(it))}
        if(url==null) {pending=null;return}
        val fingerprint=MessageDigest.getInstance("SHA-256").digest("$copiedAt\u0000$url".toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it)}
        if(url==alreadyHandling) {lastHandledFingerprint=fingerprint;pending=null;return}
        pending=ClipboardLink(url,fingerprint).takeUnless {fingerprint==lastHandledFingerprint}
    }

    fun consume() {
        pending?.let {lastHandledFingerprint=it.fingerprint}
        pending=null
    }

    fun withdraw() {pending=null}
}
