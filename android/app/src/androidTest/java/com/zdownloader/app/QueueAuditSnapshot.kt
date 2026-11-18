package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Status-only diagnostic: never export URLs, titles, cookies or selections. */
internal suspend fun queueAuditSnapshot(name:String) {
    val instrument=InstrumentationRegistry.getInstrumentation()
    val app=instrument.targetContext.applicationContext as ZApplication
    val dao=app.repository.dao
    val signals=dao.queueSignals().first()
    val rows=dao.observe().first()
    val report=JSONObject().put("coordinator_running",app.coordinator.running).put("remaining",dao.remaining())
        .put("status_counts",JSONObject(rows.groupingBy {it.status}.eachCount()))
        .put("signals",JSONArray().apply {for(signal in signals) {
            val row=rows.firstOrNull {it.id==signal.id}
            put(JSONObject().put("id",signal.id).put("status",signal.status).put("next_attempt",signal.nextAttempt)
                .put("checkpoint",row?.checkpoint).put("has_owner",app.coordinator.active(signal.id))
                .put("batch_state",row?.batchId?.let {dao.batch(it)?.state}))
        }})
    val out=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    File(out,"queue-$name.json").writeText(report.toString(2))
}
