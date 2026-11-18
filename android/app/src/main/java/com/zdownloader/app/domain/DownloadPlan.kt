package com.zdownloader.app.domain

enum class DownloadNetwork { ANY, WIFI, UNMETERED }
data class DownloadPlan(val at:Long=System.currentTimeMillis(),val network:DownloadNetwork=DownloadNetwork.ANY,val charging:Boolean=false) {
    init {require(at>=0)}
    fun eligible(now:Long,wifi:Boolean,unmetered:Boolean,isCharging:Boolean):Boolean = now>=at&&(!charging||isCharging)&&when(network) {
        DownloadNetwork.ANY->true;DownloadNetwork.WIFI->wifi;DownloadNetwork.UNMETERED->unmetered
    }
}
