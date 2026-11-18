package com.zdownloader.app.domain

/** Only discarded, settled work can lose its collection record. Completed media survives. */
object PlaylistRemovalPolicy {
    fun allowed(state:String,statuses:List<String>):Boolean =
        (state=="CANCELLED" && statuses.all {it in setOf("CANCELLED","COMPLETED")}) ||
            (statuses.isNotEmpty() && statuses.all {it=="CANCELLED"})
}
