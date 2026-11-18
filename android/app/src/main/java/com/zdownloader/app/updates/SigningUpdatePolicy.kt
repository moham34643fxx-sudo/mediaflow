package com.zdownloader.app.updates

/** A signer change requires explicitly pinned endpoints and Android-verified signing history. */
internal object SigningUpdatePolicy {
    fun compatible(current:Set<String>,target:Set<String>,history:Set<String>,sdk:Int,production:String,legacy:String):Boolean {
        if(current.isNotEmpty()&&current==target) return true
        if(sdk<28||current.size!=1||target.size!=1) return false
        if(!production.matches(Regex("[a-f0-9]{64}"))||!legacy.matches(Regex("[a-f0-9]{64}"))) return false
        if(current.single()!=legacy||target.single()!=production) return false
        return history.containsAll(current+target)
    }
}
