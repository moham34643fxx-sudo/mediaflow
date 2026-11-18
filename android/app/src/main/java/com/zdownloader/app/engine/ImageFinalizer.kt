package com.zdownloader.app.engine

import android.graphics.ImageDecoder
import android.os.Build
import com.zdownloader.app.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Validation decodes pixels, but exported bytes are always the untouched source. */
internal object ImageFinalizer {
    private const val MAX_ENCODED_BYTES=32L*1024*1024
    private const val MAX_PIXELS=32L*1024*1024

    suspend fun copy(source:File,output:File,format:Format,control:TransferControl) {
        require(source.canonicalFile!=output.canonicalFile)
        require(format.image)
        if(source.length() !in 1..MAX_ENCODED_BYTES) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_size")
        val lease=NativeWork.acquire(requireNotNull(output.parentFile))
        try {
            source.inputStream().use {input->FileOutputStream(output).use {sink->
                val buffer=ByteArray(64*1024);var copied=0L
                while(true) {
                    currentCoroutineContext().ensureActive();control.check()
                    val count=input.read(buffer);if(count<0) break
                    copied+=count
                    if(copied>MAX_ENCODED_BYTES) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_size")
                    sink.write(buffer,0,count)
                }
                if(copied!=source.length()) throw MediaException(ErrorCode.FILE_WRITE_FAILED)
                sink.fd.sync()
            }}
            validate(output,format,control)
        } finally {lease.release()}
    }

    suspend fun validate(file:File,format:Format,control:TransferControl) {
        currentCoroutineContext().ensureActive();control.check()
        if(Build.VERSION.SDK_INT<28) throw MediaException(ErrorCode.DECODER_UNAVAILABLE)
        if(file.length() !in 1..MAX_ENCODED_BYTES) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_size")
        control.onValidationReady?.invoke();control.onValidationReady=null
        val lease=NativeWork.acquire(requireNotNull(file.parentFile))
        try {
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {decoder,header,_->
                validateHeader(header,format)
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                decoder.setOnPartialImageListener {false}
            }
            try {
                currentCoroutineContext().ensureActive();control.check()
                if(bitmap.width<=0||bitmap.height<=0) throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_decode")
            } finally {bitmap.recycle()}
        } catch(error:IOException) {throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_decode")}
        catch(error:OutOfMemoryError) {throw MediaException(ErrorCode.DECODER_UNAVAILABLE)}
        finally {lease.release()}
    }

    @androidx.annotation.RequiresApi(28)
    private fun validateHeader(header:ImageDecoder.ImageInfo,format:Format) {
        if(header.isAnimated) throw MediaException(ErrorCode.FORMAT_UNAVAILABLE,validationCheck="animated_image")
        val expected=when(format.ext) {"jpg","jpeg"->"image/jpeg";"png"->"image/png";"webp"->"image/webp";else->""}
        val width=header.size.width;val height=header.size.height
        val pixels=width.toLong()*height
        val runtime=Runtime.getRuntime()
        val headroom=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        if(header.mimeType!=expected||width<=0||height<=0||pixels>MAX_PIXELS)
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_header")
        if(pixels*8+32*1024*1024>headroom) throw MediaException(ErrorCode.DECODER_UNAVAILABLE)
        val selected=listOfNotNull(format.width,format.height).sorted()
        if(selected.size!=2||selected!=listOf(width,height).sorted())
            throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="image_dimensions")
    }
}
