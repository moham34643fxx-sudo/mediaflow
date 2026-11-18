package com.zdownloader.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.playback.PlaybackRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Save and restore only MediaFlow's playback preferences around isolated process-death probes. */
class PlaybackQueuePreservationTest {
    private val arguments get()=InstrumentationRegistry.getArguments()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private fun snapshotFile():File {
        val run=arguments.getString("evidenceRun") ?: throw AssertionError("Missing evidenceRun")
        return File(app.getExternalFilesDir(null),"advanced-audit/$run/playback-prefs.json").apply {parentFile?.mkdirs()}
    }

    @Test fun capturePlaybackPrefsBeforeExternalProcessProbe() {
        assertFalse("External process test requires no active playback service",PlaybackRuntime.active)
        assertFalse("External process test must not interrupt audio",app.playback.state.value.playing)
        val entries=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE).all
        val values=JSONObject()
        entries.forEach {(key,value)->
            val row=JSONObject()
            when(value) {
                is String->row.put("type","string").put("value",value)
                is Boolean->row.put("type","boolean").put("value",value)
                is Int->row.put("type","int").put("value",value)
                is Long->row.put("type","long").put("value",value)
                is Float->row.put("type","float").put("value",value.toDouble())
                is Set<*>->row.put("type","strings").put("value",JSONArray(value.filterIsInstance<String>()))
                else->throw AssertionError("Unexpected playback preference type for $key")
            }
            values.put(key,row)
        }
        val file=snapshotFile()
        assertFalse("Refusing to overwrite an existing snapshot",file.exists())
        file.writeText(values.toString(2))
    }

    @Test fun restorePlaybackPrefsAfterExternalProcessProbe() {
        val file=snapshotFile()
        assertTrue("The exact test-owned playback preference snapshot is required",file.isFile)
        val values=JSONObject(file.readText())
        val editor=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE).edit().clear()
        values.keys().forEach {key->
            val row=values.getJSONObject(key);val value=row.get("value")
            when(row.getString("type")) {
                "string"->editor.putString(key,row.getString("value"))
                "boolean"->editor.putBoolean(key,row.getBoolean("value"))
                "int"->editor.putInt(key,row.getInt("value"))
                "long"->editor.putLong(key,row.getLong("value"))
                "float"->editor.putFloat(key,row.getDouble("value").toFloat())
                "strings"->{val array=value as JSONArray;editor.putStringSet(key,(0 until array.length()).map {array.getString(it)}.toSet())}
                else->throw AssertionError("Unexpected snapshot type for $key")
            }
        }
        assertTrue("Playback preference restoration must commit",editor.commit())
        assertTrue("Test-owned playback snapshot cleanup",file.delete()||!file.exists())
    }
}
