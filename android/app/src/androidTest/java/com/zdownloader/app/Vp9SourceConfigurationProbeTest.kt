@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.media.MediaExtractor
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.engine.WebmPacketInventory
import com.zdownloader.app.engine.Mp4PacketInventory
import com.zdownloader.app.engine.Vp9CopyConfiguration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class Vp9SourceConfigurationProbeTest {
    @Test fun retainedTracksHaveCompleteBoundedContainerInventories() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext
        val directory=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb")
        for(name in listOf("video.webm","audio.m4a")) {
            val catalog=File.createTempFile("packet-probe-",".bin",app.cacheDir)
            try {
                val inventory=if(name=="video.webm") WebmPacketInventory.write(File(directory,name),catalog) {}
                    else Mp4PacketInventory.write(File(directory,name),catalog,"audio") {}
                val report=JSONObject().put("track",name).put("packets",inventory.packets).put("end",inventory.end)
                    .put("clock_numerator",inventory.clock.numerator).put("clock_denominator",inventory.clock.denominator)
                    .put("catalog_bytes",catalog.length())
                instrument.sendStatus(2,android.os.Bundle().apply {putString("vp9_packet_inventory",report.toString())})
                assertEquals(inventory.packets*57,catalog.length())
                if(name=="video.webm") {assertEquals(21312L,inventory.packets);assertEquals(887999L,inventory.end)}
                else {assertTrue(inventory.packets>0);assertEquals(48000L,inventory.clock.denominator)}
            } finally {catalog.delete()}
        }
    }

    @Test fun retainedSourceKeyframeDerivesCanonicalConfiguration() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext
        val source=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb/video.webm")
        val facts=FFprobeKit.getMediaInformation(source.path).mediaInformation!!.streams.single {it.type=="video"}.allProperties
        val report=JSONObject()
        for(key in listOf("codec_name","profile","pix_fmt","width","height","r_frame_rate","avg_frame_rate",
            "sample_aspect_ratio","color_range","color_space","color_transfer","color_primaries","chroma_location"))
            report.put(key,facts.opt(key) ?: JSONObject.NULL)
        val audio=File(source.parentFile,"audio.m4a")
        val audioFacts=FFprobeKit.getMediaInformation(audio.path).mediaInformation!!.streams.single {it.type=="audio"}.allProperties
        report.put("audio_profile",audioFacts.opt("profile"))
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(source.path);extractor.selectTrack(0)
            val buffer=ByteBuffer.allocate(8*1024*1024)
            val size=extractor.readSampleData(buffer,0)
            assertTrue(size>=32)
            val prefix=ByteArray(32);buffer.position(0);buffer.get(prefix)
            val canonical=Vp9CopyConfiguration.canonical(prefix,640,272,facts.optString("r_frame_rate"))
            report.put("android_format",extractor.getTrackFormat(0).toString()).put("flags",extractor.sampleFlags)
                .put("prefix",prefix.joinToString("") {"%02x".format(it)})
                .put("canonical",canonical?.joinToString("") {"%02x".format(it)} ?: JSONObject.NULL)
            assertEquals("010000000015820202020000",canonical?.joinToString("") {"%02x".format(it)})
        } finally {
            extractor.release()
            instrument.sendStatus(2,android.os.Bundle().apply {putString("vp9_source_configuration",report.toString())})
        }
    }
}
