@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class MediaKindBoundaryTest {
    @Test fun imageBytesOverrideMisleadingMimeFilenameAndRedirects()=runBlocking<Unit> {
        val bitmap=Bitmap.createBitmap(24,32,Bitmap.Config.ARGB_8888)
        val bytes=try {ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}
            finally {bitmap.recycle()}
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request()
            if(request.url.encodedPath=="/redirect") Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(302).message("Redirect").header("Location","/photo.mp4").body(ResponseBody.create(null,byteArrayOf())).build()
            else Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Image bytes")
                .header("Content-Type","video/mp4").body(ResponseBody.create(null,bytes)).build()
        }.build()
        for(path in listOf("photo.png","photo.mp4","download","redirect")) {
            val media=requireNotNull(DirectMediaResolver(client).resolve("https://media-kind.example/$path",allowUnknownExtension=true))
            assertTrue(media.videoOptions().isEmpty());assertTrue(media.audioOptions().isEmpty())
            val selected=Selection.image(media,media.originalImage()!!)
            assertEquals("png",selected.output);assertEquals(24,selected.primary.width);assertEquals(32,selected.primary.height)
            assertTrue(runCatching {DownloadRequest(height=360).resolve(media)}.exceptionOrNull() is MediaException)
        }
    }

    @Test fun selectedVideoAndAudioRejectOriginalImageBytesBeforeProcessing()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"media-kind-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val image=File(root,"misleading.mp4")
            val bitmap=Bitmap.createBitmap(24,32,Bitmap.Config.ARGB_8888)
            try {image.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
            for(audio in listOf(false,true)) {
                try {Processor().validate(image,audio,requireAudio=audio);fail("Image bytes were accepted as time-based media")}
                catch(error:MediaException) {assertEquals("media_kind_mismatch",error.validationCheck)}
            }
            val format=Format("fake","mp4",32,30.0,"h264","none",image.length(),null,"https","{\"url\":\"https://example.com/file.mp4\"}",width=24)
            try {Processor().process(image,null,File(root,"output.mp4"),Selection(format,videoOnly=true),TransferControl());fail("Still image was transcoded as selected video")}
            catch(error:MediaException) {assertEquals("media_kind_mismatch",error.validationCheck)}
        } finally {root.deleteRecursively()}
    }

    @Test fun realOneFrameVideoAndAudioOnlyMp4RemainTheirActualTypes()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val root=File(instrument.targetContext.cacheDir,"media-kind-real-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val source=File(root,"source.mp4")
            instrument.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
            val video=File(root,"one-frame.mp4");val audio=File(root,"audio.m4a")
            for((output,args) in listOf(video to listOf("-map","0:v:0","-frames:v","1","-an"),audio to listOf("-map","0:a:0","-vn"))) {
                assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments((listOf("-y","-v","error","-i",source.path)+args+listOf("-c","copy","-movflags","+faststart",output.path)).toTypedArray()).returnCode))
                val bytes=output.readBytes()
                val client=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Actual media").header("Content-Type","application/octet-stream").body(ResponseBody.create(null,bytes)).build()}.build()
                val media=requireNotNull(DirectMediaResolver(client).resolve("https://media-kind.example/file",allowUnknownExtension=true))
                assertTrue(media.imageOptions().isEmpty())
                if(output==video) {assertTrue(media.videoOptions().isNotEmpty());Processor().validate(video,false,false)}
                else {assertTrue(media.videoOptions().isEmpty());assertTrue(media.audioOptions().isNotEmpty());Processor().validate(audio,true)}
            }
        } finally {root.deleteRecursively()}
    }
}
