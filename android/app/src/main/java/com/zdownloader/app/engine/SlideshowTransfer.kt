package com.zdownloader.app.engine

import android.graphics.BitmapFactory
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Sequential bounded image reads. All native inputs are generated local JPEGs. */
internal object SlideshowTransfer {
    suspend fun download(format:Format,output:File,client:OkHttpClient,control:TransferControl,progress:(Long,Long?)->Unit,onFirstNewByte:()->Unit={},
        render:suspend (File,SlideshowPlan)->Unit):Unit=coroutineScope {
        val plan=SlideshowPlan.parse(format.payload)
        val dir=File(output.parentFile,"slideshow-inputs").apply {mkdirs()}
        val network=client.newBuilder().callTimeout(30,TimeUnit.SECONDS).build()
        var bytes=0L
        val manifest=StringBuilder("ffconcat version 1.0\n")
        for((i,url) in plan.urls.withIndex()) {
            control.check();currentCoroutineContext().ensureActive()
            val call=network.newCall(Request.Builder().url(url).header("Accept-Encoding","identity").build())
            control.add(call)
            val cancellation=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
                try {awaitCancellation()} finally {call.cancel()}
            }
            val data=try {awaitResponse(call).use {response->
                if(!response.isSuccessful) throw MediaException(when(response.code) {401->ErrorCode.AUTHENTICATION_REQUIRED;403->ErrorCode.HTTP_FORBIDDEN;429->ErrorCode.RATE_LIMITED;else->ErrorCode.HTTP_ERROR})
                val body=response.body ?: throw MediaException(ErrorCode.HTTP_ERROR)
                if(body.contentLength()>8*1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                val sink=java.io.ByteArrayOutputStream()
                body.byteStream().use {input->val buffer=ByteArray(32768);while(true) {
                    control.check();currentCoroutineContext().ensureActive()
                    val n=input.read(buffer);if(n<0) break
                    if(sink.size()+n>8*1024*1024||bytes+n>128*1024*1024) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                    sink.write(buffer,0,n);onFirstNewByte();bytes+=n;progress(bytes,null)
                }}
                sink.toByteArray()
            }} catch(error:java.io.IOException) {
                control.check();currentCoroutineContext().ensureActive();throw error
            } finally {control.remove(call);cancellation.cancel()}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            if(bounds.outWidth !in 1..16384||bounds.outHeight !in 1..16384) throw MediaException(ErrorCode.SOURCE_CORRUPT)
            var sample=1;while(bounds.outWidth/sample>2048||bounds.outHeight/sample>2048) sample*=2
            val bitmap=BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: throw MediaException(ErrorCode.SOURCE_CORRUPT)
            val image=File(dir,"slide${i.toString().padStart(3,'0')}.jpg")
            // A concat image stream changing geometry can reinitialize FFmpeg's
            // fps filter mid-timeline. Normalize every frame BEFORE concat so
            // timestamps and the last slide's full duration survive unchanged.
            val canvasBitmap=android.graphics.Bitmap.createBitmap(plan.width,plan.height,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas=android.graphics.Canvas(canvasBitmap);canvas.drawColor(android.graphics.Color.BLACK)
                val scale=minOf(plan.width.toFloat()/bitmap.width,plan.height.toFloat()/bitmap.height)
                val w=bitmap.width*scale;val h=bitmap.height*scale
                val destination=android.graphics.RectF((plan.width-w)/2,(plan.height-h)/2,(plan.width+w)/2,(plan.height+h)/2)
                canvas.drawBitmap(bitmap,null,destination,android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                image.outputStream().use {if(!canvasBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)) throw MediaException(ErrorCode.FILE_WRITE_FAILED)}
            } finally {canvasBitmap.recycle();bitmap.recycle()}
            manifest.append("file '").append(image.name).append("'\nduration ").append(String.format(Locale.ROOT,"%.9f",plan.duration/plan.urls.size)).append('\n')
        }
        manifest.append("file 'slide").append((plan.urls.size-1).toString().padStart(3,'0')).append(".jpg'\n")
        val input=File(dir,"input.ffconcat");input.writeText(manifest.toString())
        control.check();render(input,plan)
        // Only our generated inputs are removed, after the native owner released them.
        NativeWork.awaitIdle(dir)
        for(i in plan.urls.indices) File(dir,"slide${i.toString().padStart(3,'0')}.jpg").delete()
        input.delete();dir.delete()
        Unit
    }
}
