package com.zdownloader.app.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostDownloadSelectionTest {
    private val hardware=MediaIntelligence.CodecCapabilities(true,true,true,true)
    private fun video(id:String,height:Int,codec:String,ext:String="mp4")=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$ext").put("ext",ext)
        .put("width",height*16/9).put("height",height).put("fps",30)
        .put("vcodec",codec).put("acodec","none").put("tbr",500))
    private fun audio(id:String,codec:String,ext:String)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.$ext").put("ext",ext)
        .put("vcodec","none").put("acodec",codec).put("abr",128))
    private val aac=audio("aac","mp4a.40.2","m4a")
    private val opus=audio("opus","opus","webm")
    private fun media(vararg formats:Format)=Media("https://example.com/watch","fixture","Video","YouTube",null,null,1170.17,formats.toList())

    // HONOR 600 incident: the old AV1 full-decode penalty must not hide copy alternatives.
    // AV1 now shares fast validation, so supported AV1 copy is no longer artificially demoted.
    @Test fun equivalentFastCopyKeepsEveryQualityTierAndAlternative() {
        val formats=listOf(144,360,720).flatMap {height->listOf(
            video("av1-$height",height,"av01.0.08M.08"),
            video("vp9-mp4-$height",height,"vp09.00.31.08"),
            video("vp9-webm-$height",height,"vp09.00.31.08","webm"))}
        val source=media(*(formats+listOf(aac,opus)).toTypedArray())
        val groups=source.qualityGroups(hardware)
        assertEquals(listOf(720,360,144),groups.map {it.resolution})
        assertEquals(formats.map {it.id}.toSet(),groups.flatMap {it.candidates}.map {it.id}.toSet())
        for(height in listOf(144,360,720)) {
            val recommended=source.recommendedVideo(height,hardware)!!
            assertEquals("av1-$height",recommended.id)
            val selection=Selection.video(source,recommended)
            assertEquals(aac,selection.secondary)
            assertEquals(ProcessingPlan.MERGE_COPY,selection.plan)
            assertEquals(recommended,MediaIntelligence.recommend(source,height,hardware))
        }
        assertEquals(groups.map {it.recommended.id}.reversed(),QuickShareCatalog.videoEntries(source,hardware).map {it.format.id})
    }

    @Test fun unsupportedVp9DoesNotDisplaceAv1ButAacCopyDoesNotRequireWebm() {
        val av1=video("av1",720,"av01.0.08M.08")
        val vp9=video("vp9",720,"vp09.00.31.08","webm")
        val source=media(av1,vp9,aac,opus)
        val noVp9=MediaIntelligence.CodecCapabilities(true,true,true,false)
        val profileRejected=MediaIntelligence.CodecCapabilities(true,true,true,true) {it.id!="vp9"}
        for(caps in listOf(noVp9,profileRejected)) assertEquals(av1,source.recommendedVideo(720,caps))
        val aacOnly=media(av1,vp9,aac)
        assertEquals(av1,aacOnly.recommendedVideo(720,hardware))
        assertEquals("mkv",Selection.video(aacOnly,vp9).output)
        assertEquals(ProcessingPlan.MERGE_COPY,Selection.video(aacOnly,vp9).plan)
        assertEquals(av1,media(av1,aac,opus).recommendedVideo(720,hardware))
    }

    @Test fun audioLanguagePreferenceIsNotSacrificedToObtainWebm() {
        val av1=video("av1",720,"av01")
        val vp9=video("vp9",720,"vp09","webm")
        val preferredAudio=Format.parse(JSONObject(aac.payload).put("language","ar").put("language_preference",10))
        val source=media(av1,vp9,preferredAudio,opus)
        assertEquals(av1,source.recommendedVideo(720,hardware))
        assertEquals(preferredAudio,Selection.video(source,vp9).secondary)
    }

    @Test fun hardwareCopyPreferenceAppliesAcrossContainersWithoutForcingOneCodec() {
        val av1=video("av1",720,"av01")
        for((codec,ext) in listOf("avc1" to "mp4","hvc1" to "mp4","hvc1" to "mkv",
            "vp09" to "webm","vp09" to "mp4","vp09" to "mkv")) {
            val fast=video("fast",720,codec,ext)
            val source=media(av1,fast,aac,opus)
            val device=MediaIntelligence.CodecCapabilities() {it.id==fast.id}
            assertEquals("$codec/$ext",fast,source.recommendedVideo(720,device))
            assertEquals(ProcessingPlan.MERGE_COPY,Selection.video(source,fast).plan)
        }
        val expensive=Format.parse(JSONObject(av1.payload).put("acodec","opus"))
        val avc=video("avc",720,"avc1")
        val source=media(expensive,avc,aac,opus)
        assertTrue(Selection.video(source,expensive).transcodeAudio)
        assertEquals(avc,source.recommendedVideo(720,hardware))
    }

    @Test fun vp8AndOtherCatalogCodecsRemainSelectableWithoutInventedHardwareSupport() {
        val vp8=video("vp8",720,"vp08.00.10.08","webm")
        val legacy=video("legacy",720,"mpeg4","mkv")
        val avc=video("avc",720,"avc1")
        val source=media(vp8,legacy,avc,opus)
        assertEquals("vp8",codecFamily(vp8.videoCodec))
        assertEquals(setOf("vp8","legacy","avc"),QuickShareCatalog.variantEntries(source).map {it.format.id}.toSet())
        assertEquals(vp8,source.recommendedVideo(720,MediaIntelligence.CodecCapabilities() {it.id=="vp8"}))
        assertEquals(legacy,Selection.video(source,legacy,"mkv").primary)
    }

    @Test fun fastPathNeverOverridesHigherDimensionsHdrOrFrameRate() {
        val vp9=video("vp9",720,"vp09","webm").copy(width=1278)
        val av1=video("av1",720,"av01").copy(width=1278)
        val betterFidelity=listOf(
            av1.copy(width=1280,height=720), // exact pixels outrank the slightly narrower fixture
            av1.copy(dynamicRange="HDR10"),
            av1.copy(fps=60.0))
        for(better in betterFidelity) assertEquals(better,media(vp9,better,opus).recommendedVideo(720,hardware))
        val higher=video("1080",1080,"av01")
        val source=media(vp9,higher,opus)
        assertEquals(higher,source.recommendedVideo(capabilities=hardware))
        assertEquals(vp9,source.recommendedVideo(720,hardware))
    }

    @Test fun explicitAv1Vp9AndContainerSelectionsSurviveRecommendationsAndSerialization() {
        val av1=video("av1",720,"av01.0.08M.08")
        val vp9=video("vp9",720,"vp09.00.31.08","webm")
        val source=media(av1,vp9,aac,opus)
        for(format in listOf(av1,vp9)) for(container in listOf("mp4","mkv")+if(format==vp9) listOf("webm") else emptyList()) {
            val selected=Selection.video(source,format,container)
            source.recommendedVideo(720,hardware)
            val restored=Selection.parse(selected.json())
            assertEquals(format,restored.primary)
            assertEquals(container,restored.output)
            assertEquals(selected.secondary,restored.secondary)
            assertEquals(selected,QuickShareChoice(false,format.id,container).resolve(source))
        }
    }
}
