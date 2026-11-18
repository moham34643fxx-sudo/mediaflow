package com.zdownloader.app

import android.content.ContentValues
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.extraction.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ExtractionServiceRemediationTest {
    @Test fun realServicePublishesPlayableAudioAndRetriesKnownMissingDestinationWithoutSource()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        val repo=app.audioExtraction
        check(repo.task.value==null) {"Do not replace an existing extraction task"}
        val home=File(app.cacheDir,"mf01-service-${UUID.randomUUID()}").apply {check(mkdirs())}
        val cases=JSONArray()
        try {
            val video=File(home,"source.mp4")
            instrument.context.assets.open("status-original.mp4").use {input->video.outputStream().use {input.copyTo(it)}}
            val sourceHash=MessageDigest.getInstance("SHA-256").digest(video.readBytes())
            for(missingExport in listOf(false,true)) DisposableDocuments().use {docs->
                val sourceUri=docs.create(docs.home,"service-source.mp4")
                docs.resolver.openOutputStream(sourceUri)!!.use {it.write(video.readBytes())}
                val source=repo.engine.inspect(sourceUri)
                val track=source.tracks.first()
                val plan=ExtractionPolicy.plan(track,if(missingExport) AudioOutput.M4A else AudioOutput.MP3,if(missingExport) 0 else 192)
                val rows=mutableListOf<Uri>()
                var ownId:String?=null
                ActivityScenario.launch(MainActivity::class.java).use {scenario->
                    scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                    try {
                        val missing=if(missingExport) docs.resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME,"mf01-deleted-${UUID.randomUUID()}.m4a")
                            put(MediaStore.MediaColumns.MIME_TYPE,"audio/mp4")
                            put(MediaStore.MediaColumns.IS_PENDING,1)
                        })!!.also {assertEquals(1,docs.resolver.delete(it,null,null))} else null
                        repo.start(source,track,plan,missing);ownId=repo.task.value!!.id
                        withTimeout(90000) {while(repo.task.value!!.phase.active) delay(50)}
                        if(missingExport) {
                            val failed=repo.task.value!!
                            assertEquals(ExtractionPhase.SAVE_FAILED,failed.phase)
                            assertNull(failed.pending)
                            assertTrue(File(repo.directory,"${failed.id}.${plan.extension}").isFile)
                            assertTrue(android.provider.DocumentsContract.deleteDocument(docs.resolver,sourceUri))
                            repo.retrySave()
                            withTimeout(90000) {while(repo.task.value!!.phase.active) delay(50)}
                        } else {
                            val unchanged=docs.resolver.openInputStream(sourceUri)!!.use {it.readBytes()}
                            assertArrayEquals(sourceHash,MessageDigest.getInstance("SHA-256").digest(unchanged))
                        }
                        val completed=repo.task.value!!
                        completed.output?.let {rows+=Uri.parse(it)}
                        assertEquals(ExtractionPhase.COMPLETED,completed.phase)
                        assertEquals(ownId,completed.id)
                        val output=File(home,"verified.${plan.extension}")
                        docs.resolver.openInputStream(Uri.parse(completed.output))!!.use {input->output.outputStream().use {input.copyTo(it)}}
                        repo.engine.validate(output,plan,track.duration,ExtractionControl())
                        val hash=MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") {"%02x".format(it)}
                        assertEquals(completed.sha256,hash)
                        val player=MediaPlayer()
                        try {
                            player.setDataSource(app,Uri.parse(completed.output));player.setVolume(0f,0f);player.prepare();player.start()
                            withTimeout(10000) {while(player.currentPosition<=0) delay(50)}
                            assertTrue(player.currentPosition>0)
                        } finally {player.release()}
                        cases.put(JSONObject().put("missing_destination_retry",missingExport).put("codec",plan.codec)
                            .put("source_removed_before_retry",missingExport).put("sha256",hash).put("android_playback_advanced",true))
                    } finally {
                        if(repo.task.value?.id==ownId) {
                            if(repo.task.value!!.phase.active) {repo.cancel(ownId);withTimeout(15000) {while(repo.task.value!!.phase.active) delay(50)}}
                            listOfNotNull(repo.task.value!!.output,repo.task.value!!.pending).mapTo(rows) {Uri.parse(it)}
                            rows.distinct().forEach {uri->
                                val exists=docs.resolver.query(uri,arrayOf("_id"),null,null,null)!!.use {it.moveToFirst()}
                                if(exists) assertEquals(1,docs.resolver.delete(uri,null,null))
                            }
                            repo.discard()
                        }
                    }
                }
            }
        } finally {
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"extraction-service-remediation.json").writeText(JSONObject().put("cases",cases).toString(2))
            check(home.deleteRecursively())
        }
    }
}
