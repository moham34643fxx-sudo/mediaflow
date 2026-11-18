package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class Mp3CoverIntegrityTest {
    @Test fun addingAFrontCoverMustRetainTheEntireAudioStream():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val directory=app.storage.temporary("cover-integrity-${UUID.randomUUID()}")
        val owned=mutableListOf<String>()
        try {
            val source=File(directory,"source.wav");val length=10*44100*2
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(length+36);put("WAVEfmt ".toByteArray());putInt(16)
                putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16)
                put("data".toByteArray());putInt(length)
            }.array()
            source.outputStream().use {it.write(header);it.write(ByteArray(length))}
            val image=File(directory,"cover.jpg")
            val bitmap=android.graphics.Bitmap.createBitmap(160,160,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.RED)
                val webp=java.io.ByteArrayOutputStream().also {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP,90,it))}.toByteArray()
                assertTrue(com.zdownloader.app.playback.EmbeddedArtwork.normalizeTemporaryCover(webp,image))
                assertEquals(0xff,image.inputStream().use {it.read()})
            }
            finally {bitmap.recycle()}
            val format=Format("cover-fixture","wav",null,null,"none","pcm_s16le",source.length(),705.6,"https","{}")
            val selection=Selection(format,audioOnly=true,output="mp3",convertAudio=true,bitrate=192,duration=10.0)
            val output=File(directory,"output.mp3")
            Processor().process(source,null,output,selection,TransferControl(),10.0,"Cover integrity",thumbnail=image)
            val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
            assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            assertTrue(kotlin.math.abs(info.duration.toDouble()-10.0)<.2)
            assertTrue(info.streams.any {it.type=="video"&&it.allProperties.optJSONObject("disposition")?.optInt("attached_pic")==1})
            val retriever=android.media.MediaMetadataRetriever()
            try {retriever.setDataSource(output.path);assertNotNull(retriever.embeddedPicture)} finally {retriever.release()}
            val limited=File(directory,"former-frame-limit.mp3")
            val previous=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",output.path,"-i",image.path,
                "-map","0:a:0","-map","1:v:0","-c:a","copy","-c:v","mjpeg","-frames:v","1",
                "-disposition:v:0","attached_pic","-id3v2_version","3",limited.path))
            val previousInfo=FFprobeKit.getMediaInformation(limited.path).mediaInformation
            val publications=org.json.JSONArray()
            repeat(2) {index->
                val id=UUID.randomUUID().toString();owned+=id
                val row=DownloadEntity(id,"https://example.com/cover-verification","Cover verification","Fixture",null,10.0,selection.json(),
                    "00${index+1} - MediaFlow cover verification $id.mp3",null,id,status="SAVING",checkpoint="VALIDATED",
                    relativeFolder="MediaFlow cover verification",bytes=output.length())
                app.repository.dao.insert(row)
                val uri=app.storage.commit(row,output,selection,TransferControl())
                assertEquals("COMPLETED",app.repository.dao.get(id)!!.status)
                app.contentResolver.query(uri,arrayOf("_size","is_pending"),null,null,null)!!.use {cursor->
                    assertTrue(cursor.moveToFirst());assertEquals(output.length(),cursor.getLong(0));assertEquals(0,cursor.getInt(1))
                }
                val published=android.media.MediaMetadataRetriever()
                try {published.setDataSource(app,uri);assertNotNull(published.embeddedPicture)} finally {published.release()}
                publications.put(JSONObject().put("position",index+1).put("published",true).put("bytes",output.length()).put("cover_readable",true))
            }
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun")}").apply {mkdirs()}
            File(folder,"mp3-cover-integrity.json").writeText(JSONObject().put("duration_s",info.duration)
                .put("bytes",output.length()).put("audio_and_cover_verified",true)
                .put("former_frame_limit_return_code",previous.returnCode?.value)
                .put("former_frame_limit_bytes",limited.length())
                .put("former_frame_limit_has_audio",previousInfo?.streams?.any {it.type=="audio"}==true)
                .put("media_store_publications",publications).toString(2))
        } finally {
            for(id in owned) app.repository.dao.get(id)?.let {row->
                if(row.status=="COMPLETED") app.storage.deleteFile(row)
                else {app.storage.cleanupPending(id);app.repository.dao.cancel(id);app.repository.dao.removeHistory(id)}
            }
            NativeWork.awaitIdle(directory);directory.deleteRecursively()
        }
    }
}
