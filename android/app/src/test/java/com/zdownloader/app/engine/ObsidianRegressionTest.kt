package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ObsidianRegressionTest {
    @Test fun directMp4UsesEncodedPixelsInsteadOfAnamorphicDisplayGeometry() {
        fun box(name:String,data:ByteArray)=ByteBuffer.allocate(8+data.size).order(ByteOrder.BIG_ENDIAN).putInt(8+data.size).put(name.toByteArray()).put(data).array()
        val movie=ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {putInt(12,1000);putInt(16,10000)}.array()
        val track=ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN).apply {putInt(72,1920 shl 16);putInt(76,1080 shl 16)}.array()
        val handler=ByteArray(12).apply {"vide".toByteArray().copyInto(this,8)}
        val sample=ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN).apply {putInt(4,1);putInt(8,36);position(12);put("avc1".toByteArray());putShort(40,640.toShort());putShort(42,360.toShort())}.array()
        val bytes=box("moov",box("mvhd",movie)+box("trak",box("tkhd",track)+box("mdia",box("hdlr",handler)+box("minf",box("stbl",box("stsd",sample))))))
        val result=Mp4Header.parse(bytes)
        assertTrue(result.complete);assertEquals(640,result.width);assertEquals(360,result.height)
    }
    @Test fun legacyMp4MustDeclareItsVideoConversion() {
        val f=Format("legacy","mp4",720,30.0,"mpeg4","aac",null,1000.0,"https","{\"url\":\"https://example.com/video.mp4\"}")
        assertEquals(ProcessingPlan.TRANSCODE_VIDEO,Selection(f).plan)
        assertNull(Selection(f).outputSize)
    }
    @Test fun betterAudioIsNotHiddenByASeparateLowQualityTrack() {
        val rich=Format("rich","mp4",720,30.0,"h264","aac",null,1500.0,"https","{\"url\":\"https://example.com/video.mp4\"}",audioBitrate=256.0)
        val low=Format("low","m4a",null,null,"none","aac",null,64.0,"https","{\"url\":\"https://example.com/audio.m4a\"}",audioBitrate=64.0)
        val media=Media("https://example.com/movie","id","title","source",null,null,60.0,listOf(rich,low))
        assertEquals(rich,Selection.audio(media,"mp3").primary)
    }
    @Test fun emptyOrTruncatedMoovDoesNotProveMetadataComplete() {
        val empty=ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(8).put("moov".toByteArray()).array()
        assertFalse(Mp4Header.parse(empty).complete)
        assertFalse(Mp4Header.parse(empty.copyOf(7)).complete)
    }
    @Test fun specialAddressesAndPublicNat64AgreeWithPolicy() {
        for(ip in listOf("3fff::1","2001:2::1","2002:7f00:1::1","224.0.0.1","64:ff9b::7f00:1")) assertFalse(ip,UrlPolicy.publicAddress(InetAddress.getByName(ip)))
        assertTrue(UrlPolicy.publicAddress(InetAddress.getByName("64:ff9b::808:808")))
        assertTrue(UrlPolicy.publicAddress(InetAddress.getByName("2001:4860:4860::8888")))
        for(url in listOf("https://127.0.0.1/","https://[::1]/","https://224.0.0.1/")) {
            try {UrlPolicy.normalize(url);fail("Private literal accepted before proxy routing")} catch(e:MediaException) {assertEquals(ErrorCode.URL_POLICY_REJECTED,e.code)}
        }
    }
    @Test fun cancellationReturnsWhileNativeFileOwnershipRemains() = runBlocking {
        val dir=File("native-lease-fixture")
        val lease=NativeWork.acquire(dir)
        val control=TransferControl()
        val waiting=async {withControlCancellation(control) {NativeWork.awaitIdle(dir)}}
        yield();control.stop()
        try {withTimeout(1000) {waiting.await()};fail("Expected cancellation")} catch(_:CancellationException) { }
        assertTrue(NativeWork.busy(dir))
        lease.release()
        withTimeout(1000) {NativeWork.awaitIdle(dir)}
        assertFalse(NativeWork.busy(dir))
    }
    @Test fun queuedNativeWorkReservesFilesBeforeDispatchAndCannotStartAfterCancellation() = runBlocking {
        val dir=File("native-reservation-pending")
        val reservation=NativeWork.Reservation(dir)
        assertTrue(NativeWork.busy(dir))
        val cleanup=async {NativeWork.awaitIdle(dir);true}
        yield();assertFalse(cleanup.isCompleted)
        reservation.cancelPending()
        assertTrue(withTimeout(1000) {cleanup.await()})
        assertFalse(reservation.start())
        assertFalse(NativeWork.busy(dir))
    }
    @Test fun cancellingStartedNativeWorkDoesNotReleaseItsFiles() = runBlocking {
        val dir=File("native-reservation-running")
        val reservation=NativeWork.Reservation(dir)
        assertTrue(reservation.start())
        reservation.cancelPending()
        assertTrue(NativeWork.busy(dir))
        reservation.finish()
        withTimeout(1000) {NativeWork.awaitIdle(dir)}
        assertFalse(NativeWork.busy(dir))
    }
}
