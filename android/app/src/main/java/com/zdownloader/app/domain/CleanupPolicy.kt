package com.zdownloader.app.domain

object CleanupPolicy {
    fun eligible(status:String?,active:Boolean,nativeBusy:Boolean,ageMillis:Long):Boolean = !active&&!nativeBusy&&when(status) {
        "COMPLETED","CANCELLED"->true
        null->ageMillis>=24*60*60_000L
        else->false // Failed and paused rows may contain recoverable transfer/processing checkpoints.
    }
}
