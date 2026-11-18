@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import androidx.room.Room
import com.zdownloader.app.data.*
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Real decoder baseline followed by packet-proof mutations; no user media is edited. */
class CopyOutputVerificationSafetyTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication

    @Test fun changedPacketsTimingAndSourcesNeverReceiveAnOutputCertificate()=runBlocking<Unit> {
        val original=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb")
        val root=File(app.cacheDir,"copy-proof-safety-${UUID.randomUUID()}").apply {check(mkdirs())}
        val video=File(root,"source.webm");val audio=File(root,"source.m4a")
        File(original,"video.webm").copyTo(video);File(original,"audio.m4a").copyTo(audio)
        assertEquals("568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83",hash(video))
        assertEquals("be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703",hash(audio))
        val processor=Processor()
        val sourceControl=TransferControl()
        val output=File(root,"output.mp4")
        val media=FFprobeKit.getMediaInformation(video.path).mediaInformation!!
        val geometry=media.streams.single {it.type=="video"}
        fun format(file:File,isVideo:Boolean)=Format.parse(JSONObject().put("format_id",if(isVideo) "video" else "audio")
            .put("url","https://copy-proof.example/${file.name}").put("ext",file.extension).put("protocol","https")
            .put("vcodec",if(isVideo) "vp9" else "none").put("acodec",if(isVideo) "none" else "aac")
            .put("width",if(isVideo) geometry.width else null).put("height",if(isVideo) geometry.height else null).put("filesize",file.length()))
        val selection=Selection(format(video,true),format(audio,false),output="mp4",duration=media.duration.toDouble(),embedMetadata=false)
        try {
            processor.validate(video,false,false,control=sourceControl)
            processor.validate(audio,true,true,control=sourceControl)
            processor.process(video,audio,output,selection,sourceControl,selection.duration)
            val sources=CopyVerification.Sources(
                CopyVerification.DecodedSource(video,video.length(),hash(video),21312,888000),
                CopyVerification.DecodedSource(audio,audio.length(),hash(audio),0,888000))
            fun control()=TransferControl().also {it.copySources=sources}
            val validHash=CopyOutputVerification.verify(output,selection,control())
            assertEquals("The untouched copy must qualify; fallback is not acceptance",hash(output),validHash)
            val changed=File(root,"changed.mp4")
            for(kind in listOf("payload","truncate","wrong_source","cancelled")) {
                output.copyTo(changed,overwrite=true)
                val candidate=control()
                when(kind) {
                    "payload"->RandomAccessFile(changed,"rw").use {file->
                        val offset=mdatPayload(changed)+1024
                        file.seek(offset);val byte=file.readByte();file.seek(offset);file.writeByte(byte.toInt() xor 1)
                    }
                    "truncate"->RandomAccessFile(changed,"rw").use {it.setLength(it.length()-4096)}
                    "wrong_source"->candidate.copySources=sources.copy(video=sources.video.copy(sha256="0".repeat(64)))
                    "cancelled"->candidate.stop()
                }
                try {
                    assertNull("Unsafe output received a certificate: $kind",CopyOutputVerification.verify(changed,selection,candidate))
                    assertNotEquals("Cancellation must propagate, not return usable evidence","cancelled",kind)
                } catch(error:CancellationException) {assertEquals("cancelled",kind)}
            }
            val mutated=File(root,"timing.mp4")
            output.copyTo(mutated)
            mutateTimingTable(mutated)
            assertNull("Changed sample timing received a certificate",CopyOutputVerification.verify(mutated,selection,control()))
            for(kind in listOf("profile","range","aspect","edit","roll")) {
                output.copyTo(changed,overwrite=true)
                mutatePlaybackContract(changed,kind)
                assertNull("Changed playback configuration received a certificate: $kind",CopyOutputVerification.verify(changed,selection,control()))
            }
            output.copyTo(changed,overwrite=true)
            RandomAccessFile(changed,"rw").use {input->
                val offset=mdatPayload(changed)+1024;input.seek(offset);val byte=input.readByte()
                input.seek(offset);input.writeByte(byte.toInt() xor 1)
            }
            rejectChangedPublication(root,changed,selection,requireNotNull(validHash))
            assertFalse(CopyOutputVerification.eligible(selection.copy(output="mkv")))
            assertFalse(CopyOutputVerification.eligible(selection.copy(secondary=selection.secondary!!.copy(audioCodec="opus"))))
        } finally {
            sourceControl.stop()
            check(!NativeWork.busy(root)) {"Native reader still owns safety fixtures"}
            root.deleteRecursively()
        }
    }

    private data class Mutation(val offset:Long,val width:Int,val expected:Long,val replacement:Long)
    private fun trackParts(reader:BoundedMediaFile,audio:Boolean):List<BoundedMediaFile.Region> {
        val moov=reader.one(reader.boxes(),"moov")
        val track=reader.children(moov).filter {it.kind==BoundedMediaFile.tag("trak")}.single {trak->
            val mdia=reader.one(reader.children(trak),"mdia")
            val handler=reader.one(reader.children(mdia),"hdlr")
            reader.unsigned(handler.body+8,4)==BoundedMediaFile.tag(if(audio) "soun" else "vide")
        }
        return reader.children(track)
    }
    private fun mutatePlaybackContract(file:File,kind:String) {
        val mutation=BoundedMediaFile(file) {}.use {reader->
            val parts=trackParts(reader,kind=="roll")
            val mdia=reader.children(reader.one(parts,"mdia"))
            val minf=reader.children(reader.one(mdia,"minf"))
            val tables=reader.children(reader.one(minf,"stbl"))
            val mutation=when(kind) {
                "edit"->{val elst=reader.one(reader.children(reader.one(parts,"edts")),"elst");Mutation(elst.body+12,4,0,1)}
                "roll"->{val group=reader.one(tables,"sgpd");Mutation(group.body+16,2,65535,0)}
                else->{
                    val description=reader.one(tables,"stsd")
                    val entry=reader.one(reader.boxes(description.body+8,description.end),"vp09")
                    val children=reader.boxes(entry.body+78,entry.end)
                    when(kind) {
                        "aspect"->Mutation(reader.one(children,"pasp").body,4,639,640)
                        "profile"->Mutation(reader.one(children,"vpcC").body+4,1,0,1)
                        "range"->Mutation(reader.one(children,"vpcC").body+6,1,130,131)
                        else->error("Unknown mutation")
                    }
                }
            }
            assertEquals("Mutation must target the expected real container field",mutation.expected,reader.unsigned(mutation.offset,mutation.width))
            mutation
        }
        RandomAccessFile(file,"rw").use {input->
            input.seek(mutation.offset)
            for(index in mutation.width-1 downTo 0) input.writeByte((mutation.replacement ushr (index*8)).toInt())
        }
    }
    private suspend fun rejectChangedPublication(root:File,changed:File,selection:Selection,certificate:String) {
        val context=object:ContextWrapper(app) {override fun getFilesDir()=File(root,"publication").apply {mkdirs()}}
        val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val token=UUID.randomUUID().toString()
        val row=DownloadEntity(token,"https://copy-proof.example/publication","Copy proof publication QA","QA",null,
            selection.duration,selection.json(),"Copy proof publication QA $token.mp4",null,token)
        val dao=database.downloads();val storage=StorageRepository(context,dao)
        val control=TransferControl().also {it.verifiedOutputSha256=certificate}
        try {
            dao.insert(row)
            try {storage.commit(row,changed,selection,control);fail("Changed certified bytes were published")}
            catch(error:MediaException) {
                assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED,error.code)
                assertEquals("copy_proof_file_changed",error.validationCheck)
            }
            assertNull("Rejected export left a pending publication",dao.get(token)?.pendingUri)
            assertNull("Rejected export was marked published",dao.get(token)?.uri)
        } finally {withContext(NonCancellable) {storage.cleanupPending(token);database.close()}}
    }

    private fun mdatPayload(file:File):Long=RandomAccessFile(file,"r").use {input->
        var offset=0L
        while(offset+8<=input.length()) {
            input.seek(offset);var size=input.readInt().toLong() and 0xffffffffL
            val type=ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            var header=8
            if(size==1L) {size=input.readLong();header=16}
            if(size==0L) size=input.length()-offset
            check(size>=header&&size<=input.length()-offset)
            if(type=="mdat") return@use offset+header
            offset+=size
        }
        error("No mdat in real mux output")
    }

    private fun mutateTimingTable(file:File) {
        // Only a private test-owned copy is read into memory; production proofs never do this.
        val bytes=file.readBytes()
        val marker="stts".toByteArray(Charsets.US_ASCII)
        val position=(0 until bytes.size-20).first {offset->marker.indices.all {bytes[offset+it]==marker[it]}}
        RandomAccessFile(file,"rw").use {input->
            input.seek(position+16L);val duration=input.readInt()
            input.seek(position+16L);input.writeInt(duration+1)
        }
    }

    private fun hash(file:File):String=file.inputStream().use {input->
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        digest.digest().joinToString("") {"%02x".format(it)}
    }
}
