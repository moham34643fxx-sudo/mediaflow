package com.zdownloader.app

import android.content.ContentValues
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Reproduces the device provider contract that rejected a fully validated audio WebM. */
class WebmPublicationProviderTest {
    @Test fun recordsWhichPendingAudioWebmDestinationsTheProviderAccepts() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "webm-provider/$run").also { check(it.mkdirs()) }
        val audio=audioFixture(directory)
        val rows = JSONArray()
        val destinations = listOf(
            Triple("audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/MediaFlow"),
            Triple("files", MediaStore.Files.getContentUri("external"), "Music/MediaFlow"),
            Triple("downloads", MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/MediaFlow")
        )
        for ((label, collection, folder) in destinations) {
            val row = JSONObject().put("collection", label).put("mime", "audio/webm").put("folder", folder)
            rows.put(row)
            var uri: android.net.Uri? = null
            try {
                uri = requireNotNull(app.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, ".provider-${UUID.randomUUID()}.pending.webm")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/webm")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                    check(it.moveToFirst()); row.put("stored_mime", it.getString(0)).put("stored_relative_path", it.getString(1))
                }
                row.put("accepted", true)
                app.contentResolver.openOutputStream(uri,"w")!!.use {output->audio.inputStream().use {it.copyTo(output)}}
                val finalName="provider-${UUID.randomUUID()}.webm"
                check(app.contentResolver.update(uri,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);put(MediaStore.MediaColumns.IS_PENDING,0)
                },null,null)==1)
                row.put("published_type",app.contentResolver.getType(uri))
                row.put("mime_reset_count",app.contentResolver.update(uri,ContentValues().apply {
                    put(MediaStore.MediaColumns.MIME_TYPE,"audio/webm")
                },null,null))
                row.put("after_reset_type",app.contentResolver.getType(uri))
                app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.MIME_TYPE),null,null,null)!!.use {
                    check(it.moveToFirst());row.put("after_reset_stored_mime",it.getString(0))
                }
            } catch (error: Exception) {
                row.put("accepted", false).put("exception", error.javaClass.simpleName).put("message", error.message)
            } finally {
                uri?.let { check(app.contentResolver.delete(it, null, null) == 1) }
            }
        }
        File(directory, "result.json").writeText(JSONObject().put("destinations", rows).toString(2))
    }

    @Test fun validatedAudioWebmPublishesTruthfulMimeWithoutChangingBytes()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val home=File(app.cacheDir,"webm-publication-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val context=object:ContextWrapper(app) {override fun getFilesDir()=home}
        val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val storage=StorageRepository(context,database.downloads())
        var published:Uri?=null
        try {
            val audio=audioFixture(home)
            val format=Format.parse(JSONObject().put("format_id","opus").put("url","https://example.com/audio.webm")
                .put("ext","webm").put("vcodec","none").put("acodec","opus").put("protocol","https"))
            val selection=Selection(format,audioOnly=true,output="webm",embedMetadata=false)
            Processor().validateSelection(audio,selection,TransferControl(),null)
            val id="webm-publication-${UUID.randomUUID()}"
            val row=DownloadEntity(id,"https://example.com/audio.webm","Audio WebM QA","test",null,null,
                selection.json(),"$id.webm",null,id,status="PROCESSING",checkpoint="VALIDATED")
            database.downloads().insert(row)
            val uri=storage.commit(row,audio,selection,TransferControl());published=uri
            assertTrue(uri.toString().contains("/downloads/"))
            assertEquals("audio/webm",app.contentResolver.getType(uri))
            app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.MIME_TYPE),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals("audio/webm",it.getString(1))
            }
            val proof=File(home,"published.webm")
            app.contentResolver.openInputStream(uri)!!.use {input->proof.outputStream().use {input.copyTo(it)}}
            assertArrayEquals(audio.readBytes(),proof.readBytes())
            val decoded=FFmpegKit.executeWithArguments(arrayOf("-nostdin","-v","error","-xerror","-err_detect","explode",
                "-i",proof.path,"-map","0:a:0","-max_error_rate","0","-f","null","-"))
            assertTrue("Published audio failed full decode",ReturnCode.isSuccess(decoded.returnCode))
            val player=MediaPlayer()
            try {
                player.setDataSource(app,uri);player.setVolume(0f,0f);player.prepare();player.start()
                withTimeout(8_000) {while(player.currentPosition<=0) delay(50)}
            } finally {player.release()}
            delay(1_000)
            assertEquals("audio/webm",app.contentResolver.getType(uri))
            assertEquals("COMPLETED",database.downloads().get(id)?.status)
        } finally {
            published?.let {assertEquals(1,app.contentResolver.delete(it,null,null))}
            database.close();check(home.deleteRecursively())
        }
    }

    private fun audioFixture(directory:File):File {
        val source=File(directory,"source.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("av1-validation.mp4").use {input->
            source.outputStream().use {input.copyTo(it)}
        }
        val audio=File(directory,"audio.webm")
        val encoded=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",source.path,"-vn","-c:a","opus","-strict","-2",audio.path))
        check(ReturnCode.isSuccess(encoded.returnCode)) {"Could not create the real audio-only WebM probe"}
        return audio
    }
}
