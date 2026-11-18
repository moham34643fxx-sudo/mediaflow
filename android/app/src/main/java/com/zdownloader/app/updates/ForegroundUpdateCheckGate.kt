package com.zdownloader.app.updates

/** A fresh process checks on entry even if yesterday's journal check was recent.
 * Suppress rapid Activity resumes without persisting a long discovery delay.
 */
internal class ForegroundUpdateCheckGate(private val minimumIntervalMillis:Long=60_000L) {
    private var lastCheck:Long?=null
    fun allow(elapsedMillis:Long):Boolean {
        val previous=lastCheck
        if(previous!=null && elapsedMillis>=previous && elapsedMillis-previous<minimumIntervalMillis) return false
        lastCheck=elapsedMillis
        return true
    }
}
