package com.zdownloader.app.engine

import android.util.AtomicFile
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.domain.Selection
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Private request-bound timing survives a MERGED checkpoint with removed inputs. */
internal object AudioCopyContract {
    private fun file(output:File)=AtomicFile(File(output.parentFile,"${output.name}.aac-copy-timing.json"))
    private fun fingerprint(selection:Selection)=MessageDigest.getInstance("SHA-256")
        .digest(selection.json().toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}

    fun persist(output:File,selection:Selection,timing:AacCopyTiming?) {
        val atomic=file(output)
        if(timing==null) {atomic.delete();return}
        val payload=JSONObject().put("version",1).put("selectionSha256",fingerprint(selection)).put("timing",timing.json())
        val stream=atomic.startWrite()
        try {stream.write(payload.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
        catch(error:IOException) {atomic.failWrite(stream);throw error}
        if(expected(output,selection)?.json()?.toString()!=timing.json().toString())
            throw MediaException(ErrorCode.FILE_WRITE_FAILED)
    }

    fun expected(output:File,selection:Selection):AacCopyTiming? {
        val atomic=file(output)
        if(!atomic.baseFile.exists()&&!File(atomic.baseFile.path+".bak").exists()) return null
        try {
            val contract=JSONObject(readBounded(atomic))
            if(contract.getInt("version")!=1||contract.getString("selectionSha256")!=fingerprint(selection)) invalid()
            return AacCopyTiming.fromContract(contract.getJSONObject("timing")) ?: invalid()
        } catch(error:IOException) {invalid()}
        catch(error:JSONException) {invalid()}
    }

    private fun readBounded(file:AtomicFile):String=file.openRead().use {input->
        val bytes=ByteArrayOutputStream();val buffer=ByteArray(1024)
        while(true) {
            val count=input.read(buffer);if(count<0) break
            if(bytes.size()+count>4096) invalid()
            bytes.write(buffer,0,count)
        }
        bytes.toString(Charsets.UTF_8.name())
    }

    private fun invalid():Nothing=throw MediaException(ErrorCode.OUTPUT_VALIDATION_FAILED,validationCheck="audio_timing_contract")
}
