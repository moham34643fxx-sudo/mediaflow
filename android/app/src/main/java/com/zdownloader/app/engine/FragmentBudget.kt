package com.zdownloader.app.engine

/** Share the fragment budget among actual network work, not unused queue slots. */
internal object FragmentBudget {
    fun perTrack(activeDownloads:Int,tracks:Int,constrained:Boolean=false):Int {
        require(activeDownloads>=1&&tracks in 1..2)
        val budget=if(constrained) 8 else 32
        return (budget/activeDownloads/tracks).coerceIn(1,budget)
    }
}
