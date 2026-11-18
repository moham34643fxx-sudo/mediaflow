package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SizeForecastTest {
    private fun video(id:String,height:Int,bytes:Long?)=Format.parse(JSONObject().put("format_id",id)
        .put("url","https://example.com/$id.mp4").put("ext","mp4").put("width",height*16/9).put("height",height)
        .put("vcodec","h264").put("acodec","none").put("filesize",bytes))
    private val audio=Format.parse(JSONObject().put("format_id","audio").put("url","https://example.com/audio.m4a")
        .put("ext","m4a").put("vcodec","none").put("acodec","aac").put("filesize",1_000_000).put("abr",128))
    private fun media(vararg formats:Format)=Media("https://example.com/watch","fixture","Video","Fixture",null,null,60.0,formats.toList())

    @Test fun selectedResolutionIncludesItsOwnVideoAndTheActualCompanionAudio() {
        val high=video("1080",1080,80_000_000);val low=video("720",720,40_000_000)
        val source=media(high,low,audio)
        val highSize=SizeForecast.from(source,PlaylistPolicy(height=1080))
        val lowSize=SizeForecast.from(source,PlaylistPolicy(height=720))
        assertEquals(MediaSize(81_000_000,false),highSize.transfer)
        assertEquals(MediaSize(81_000_000,true),highSize.output)
        assertEquals(MediaSize(41_000_000,true),lowSize.output)
        assertEquals(720,lowSize.quality)
    }

    @Test fun progressiveAudioIsNotCountedTwice() {
        val muxed=video("muxed",720,20_000_000).copy(audioCodec="aac")
        assertEquals(MediaSize(20_000_000,true),Selection.video(media(muxed,audio),muxed).outputSize)
    }

    @Test fun constantRateMp3UsesDurationAndChosenEncoderRateInsteadOfSourceBytes() {
        val source=media(video("video",720,90_000_000),audio)
        for(rate in listOf(128,192,256,320)) {
            val policy=PlaylistPolicy(audio=true,output="mp3",bitrate=rate)
            val predicted=SizeForecast.from(source,policy)
            assertEquals(MediaSize(60L*rate*1000/8,true),predicted.output)
            assertEquals(policy.durationOutput(60.0),predicted.output)
            assertEquals(1_000_000L,predicted.transfer!!.bytes)
        }
    }

    @Test fun missingVideoTrackDoesNotBecomeAnAudioOnlyOrArbitraryResolutionSize() {
        val source=media(video("unknown",720,null),audio)
        assertNull(SizeForecast.from(source,PlaylistPolicy()).output)
        assertNull(SizeCoverage.of(listOf(MediaSize(123,true),null)).total)
        assertEquals(MediaSize(123,true),SizeCoverage.of(listOf(MediaSize(123,true),null)).known)
        assertEquals(1,SizeCoverage.of(listOf(MediaSize(123,true),null)).measured)
    }

    @Test fun strictAndCeilingAndBestCatalogsUseTheDownloadSelectionContract() {
        val source=media(video("1080",1080,80_000_000),video("480",480,20_000_000),audio)
        val catalog=playlistSizeCatalog(source)
        val strict=PlaylistPolicy(height=720,fallback=QualityFallback.EXACT)
        assertEquals(ErrorCode.FORMAT_UNAVAILABLE,catalog[strict.sizeKey]!!.error)
        assertNull(catalog[strict.sizeKey]!!.output)
        assertEquals(480,catalog[PlaylistPolicy(height=720).sizeKey]!!.quality)
        assertEquals(1080,catalog[PlaylistPolicy(fallback=QualityFallback.BEST).sizeKey]!!.quality)
        for(policy in listOf(PlaylistPolicy(height=480),PlaylistPolicy(height=1080),PlaylistPolicy(audio=true))) {
            assertEquals(policy.resolve(source).outputSize,catalog[policy.sizeKey]!!.output)
        }
    }

    @Test fun aggregationPropagatesUnknownsEstimatesAndOverflowWithoutInventingTotals() {
        assertEquals(MediaSize(30,false),sumMediaSizes(listOf(MediaSize(10,false),MediaSize(20,false))))
        assertEquals(MediaSize(30,true),sumMediaSizes(listOf(MediaSize(10,false),MediaSize(20,true))))
        assertNull(sumMediaSizes(listOf(MediaSize(Long.MAX_VALUE,false),MediaSize(1,false))))
        assertNull(sumMediaSizes(listOf(null,MediaSize(20,true))))
        assertNull(SizeCoverage.of(emptyList()).total)
        assertEquals(MediaSize(30,true),SizeCoverage.of(listOf(MediaSize(10,false),MediaSize(20,true))).total)
    }

    @Test fun estimatedSourceRatesAndActualFileLengthsAreNotInterchanged() {
        val video=video("source",720,null).copy(videoBitrate=1000.0)
        val exact=video.copy(bytes=8_000_000)
        assertEquals(MediaSize(8_500_000,true),Selection.video(media(video,audio),video).outputSize)
        assertEquals(MediaSize(9_000_000,true),Selection.video(media(exact,audio),exact).outputSize)
    }

    @Test fun videoOnlyAndAacTranscodingFollowTheActualProcessingPlan() {
        val track=video("source",720,8_000_000)
        assertEquals(MediaSize(8_000_000,true),Selection.video(media(track,audio),track).copy(videoOnly=true,secondary=null).outputSize)
        val opus=audio.copy(id="opus",audioCodec="opus",ext="webm")
        assertEquals(MediaSize(9_440_000,true),Selection(track,opus,output="mp4",duration=60.0).outputSize)
        val combined=track.copy(audioCodec="aac",videoBitrate=null)
        assertNull(Selection.video(media(combined),combined).copy(videoOnly=true).outputSize)
    }

    @Test fun unknownDurationVbrAndLosslessConversionStayUnknown() {
        assertNull(PlaylistPolicy(audio=true,output="mp3").durationOutput(null))
        assertNull(PlaylistPolicy(audio=true,output="mp3").durationOutput(Double.NaN))
        assertNull(Selection.audio(media(audio),"mp3").copy(bitrate=0).outputSize)
        assertNull(Selection.audio(media(audio),"flac").outputSize)
    }

    @Test fun forecastPersistenceContainsSizesAndPolicyNotTransportCredentials() {
        val forecast=SizeForecast.from(media(video("720",720,40_000_000),audio),PlaylistPolicy())
        val json=forecast.json()
        assertEquals(forecast,SizeForecast.parse(JSONObject(json.toString())))
        assertFalse(json.toString().contains("https://"))
        assertFalse(json.toString().contains("cookie"))
        val rejected=SizeForecast(null,null,null,ErrorCode.FORMAT_UNAVAILABLE)
        assertEquals(rejected,SizeForecast.parse(rejected.json()))
    }
}
