package com.zdownloader.app.engine

import android.os.SystemClock
import org.json.JSONObject

/** Bounded, URL/header-free timing records. Times use the same monotonic device clock. */
object AnalysisTrace {
    @JvmStatic fun checkpoint(id:String,stage:String) {
        val now=SystemClock.elapsedRealtime()
        if(mark(id,stage,now)) NetworkDiagnostics.record("analysis",stage,null,operation=id,details=JSONObject().put("elapsed_realtime_ms",now))
    }
    private val rows=linkedMapOf<String,JSONObject>()
    @Synchronized fun mark(id:String,stage:String,at:Long=SystemClock.elapsedRealtime()):Boolean {
        if(id.isBlank()) return false
        val row=rows.getOrPut(id) {JSONObject().put("id",id)}
        if(row.has(stage)) return false
        row.put(stage,at)
        while(rows.size>32) rows.remove(rows.keys.first())
        return true
    }
    @Synchronized fun value(id:String,key:String,value:Any) {
        mark(id,"trace_created")
        rows[id]?.put(key,value)
    }
    @Synchronized fun snapshot():String=org.json.JSONArray(rows.values.toList()).toString(2)
    @Synchronized fun clear() {rows.clear()}
}
