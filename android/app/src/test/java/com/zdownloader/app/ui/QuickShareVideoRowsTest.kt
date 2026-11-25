package com.zdownloader.app.ui

import com.zdownloader.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickShareVideoRowsTest {
    private fun video(id:String,edge:Int,label:String?=null)=Format.parse(JSONObject()
        .put("format_id",id).put("url","https://example.com/$id.mp4").put("ext","mp4")
        .put("width",edge).put("height",edge*16/9).put("vcodec","avc1").put("acodec","aac")
        .put("filesize",1000).put("mediaflow_source_quality_label",label))
    private fun media(vararg rows:Format)=Media("https://example.com/watch","fixture","Fixture","Generic",null,null,10.0,rows.toList())

    @Test fun selectedVariantKeepsItsSourceKeyAcrossCompactTierAndExpandedLayouts() {
        val source=media(video("720-a",720).copy(videoBitrate=500.0),video("720-b",720).copy(videoBitrate=1500.0),video("1080",1080))
        val choice=QuickShareChoice(false,"720-b")
        val groups=QuickShareCatalog.verifiedGroups(source)
        val compact=QuickShareVideoRows.compact(groups,choice).single {it.selected}
        val tier=QuickShareVideoRows.expanded(groups,choice,setOf(QuickShareVideoRows.tierKey(720))).single {it.selected}
        val expanded=QuickShareVideoRows.variants(tier.group,choice).single {it.selected}

        assertEquals("video-source:720-b",compact.key);assertEquals(compact.key,expanded.key)
        assertEquals(720,compact.preferredHeight);assertNull(expanded.preferredHeight)
        assertEquals("share_video_720",compact.tag);assertEquals("share_video_variant_720-b",expanded.tag)
        assertEquals("share_video_quality_720",tier.tag);assertEquals("share_video_quality_toggle_720",tier.toggleTag)
        assertTrue(tier.expanded);assertEquals(choice,compact.choice);assertEquals(choice,tier.choice);assertEquals(choice,expanded.choice)
    }

    @Test fun expandedTierRowsAreDescendingAndExposeEverySourceOnlyWhenExpanded() {
        val source=media(video("big",1080),video("small-a",360),video("small-b",360))
        val groups=QuickShareCatalog.verifiedGroups(source)
        val collapsed=QuickShareVideoRows.expanded(groups,null,emptySet())
        assertEquals(listOf(1080,360),collapsed.map {it.group.resolution})
        assertEquals(2,collapsed.size)
        assertTrue(collapsed.none {it.expanded})

        val expanded=QuickShareVideoRows.expanded(groups,null,collapsed.mapTo(mutableSetOf()) {it.key})
        val variants=expanded.filter {it.expanded}.flatMap {QuickShareVideoRows.variants(it.group,null)}
        val eligible=QuickShareCatalog.variantEntries(source).map {it.format}
        assertEquals(eligible.map {it.id}.toSet(),variants.map {it.format.id}.toSet())
        assertEquals(eligible.size,variants.size)
        assertEquals(variants.size,variants.map {it.key}.distinct().size)
        assertTrue(variants.all {it.expanded&&!it.selected&&it.preferredHeight==null})
        assertEquals(variants.map {it.format.id}.toSet(),variants.map {it.choice.formatId}.toSet())
    }

    @Test fun unverifiedTierFollowsNumericTiersWithoutDuplicatingResolutionRows() {
        val source=media(video("1080-a",1080),video("360-a",360),video("720-a",720),
            video("720-b",720),video("unknown",720).copy(width=null,height=null))
        val tiers=QuickShareVideoRows.expanded(source.qualityGroups(),null,emptySet())

        assertEquals(listOf(1080,720,360,null),tiers.map {it.group.resolution})
        assertEquals(4,tiers.map {it.key}.distinct().size)
        assertEquals(2,tiers.single {it.group.resolution==720}.group.candidates.size)
        assertEquals("unknown",tiers.last().group.candidates.single().id)
    }

    @Test fun quickShareUsesOneMediaModeAndDefaultsToAnAvailableSection() {
        assertEquals(QuickShareVisibleSections(true,false,true),
            QuickShareFormatSections.resolve("all",true,true,false))
        assertEquals(QuickShareVisibleSections(false,true,true),
            QuickShareFormatSections.resolve("audio",true,true,false))
        assertEquals(QuickShareVisibleSections(false,true,true),
            QuickShareFormatSections.resolve("all",true,true,true))
        assertEquals(QuickShareVisibleSections(false,true,false),
            QuickShareFormatSections.resolve("video",false,true,false))
        assertEquals(QuickShareVisibleSections(true,false,false),
            QuickShareFormatSections.resolve("audio",true,false,true))
    }

    @Test fun customOutputAndAudioChoiceAreNotConfusedWithAnotherVideoTier() {
        val source=media(video("a",720),video("b",1080))
        val choice=QuickShareChoice(false,"a","mkv")
        val tiers=QuickShareVideoRows.expanded(QuickShareCatalog.verifiedGroups(source),choice,emptySet())
        assertEquals("mkv",tiers.single {it.selected}.choice.output)
        assertEquals("mkv",QuickShareVideoRows.variants(tiers.single {it.selected}.group,choice).single {it.selected}.choice.output)
        assertFalse(QuickShareVideoRows.expanded(QuickShareCatalog.verifiedGroups(source),QuickShareChoice(true,"a","mp3"),emptySet()).any {it.selected})
    }

    @Test fun compactCapUsesRepresentationQualityFromSourceLabel() {
        val source=media(video("compressed",360,"360p"))
        val row=QuickShareVideoRows.compact(QuickShareCatalog.verifiedGroups(source),null).single()
        assertEquals(360,QuickShareCatalog.numericVideoQuality(row.format))
        assertEquals(360,row.preferredHeight)
    }

    @Test fun sizeSnapshotsDoNotChangeTierOrVariantKeysOrRetainOldInstances() {
        val first=video("same",720)
        val next=Format.parse(JSONObject(first.payload).put("filesize",99999))
        val old=QuickShareVideoRows.expanded(QuickShareCatalog.verifiedGroups(media(first)),null,emptySet()).single()
        val updated=QuickShareVideoRows.expanded(QuickShareCatalog.verifiedGroups(media(next)),null,emptySet()).single()
        assertEquals(old.key,updated.key);assertSame(next,updated.format)
        assertEquals("video-source:same",QuickShareVideoRows.variants(updated.group,null).single().key)
        assertEquals(99999L,updated.format.bytes)
    }

    @Test fun selectedAlternativeDoesNotReplaceItsResolutionPeers() {
        val alt=video("1080-z-alt",1080).copy(videoBitrate=5000.0)
        val source=media(video("1080-a-main",1080).copy(videoBitrate=1000.0),alt,video("720",720),video("480",480))
        val choice=QuickShareChoice(false,alt.id)
        val tiers=QuickShareVideoRows.expanded(QuickShareCatalog.verifiedGroups(source),choice,emptySet())
        val selectedTier=tiers.single {it.selected}
        val selected=QuickShareVideoRows.variants(selectedTier.group,choice).single {it.selected}
        assertEquals(1080,selectedTier.group.resolution)
        assertEquals(alt.id,selected.format.id)
        assertEquals(choice,selected.choice)
        assertNotNull(selected.choice.resolve(source))
        assertEquals(alt.id,selected.choice.resolve(source)?.primary?.id)
        assertEquals(QuickShareCatalog.variantEntries(source).size,tiers.sumOf {QuickShareVideoRows.variants(it.group,choice).size})
    }

    @Test fun missingSelectionNeverSelectsAnUnrelatedTierAndUnratedSourceRemainsAvailable() {
        val unknown=video("unknown",720).copy(width=null,height=null)
        val source=media(video("a",720),video("b",720),unknown)
        val groups=source.qualityGroups()
        assertFalse(QuickShareVideoRows.expanded(groups,QuickShareChoice(false,"absent"),emptySet()).any {it.selected})
        val rows=QuickShareVideoRows.expanded(groups,QuickShareChoice(false,"unknown"),emptySet())
        val selected=rows.single {it.selected}
        assertNull(selected.group.resolution)
        assertEquals("unknown",QuickShareVideoRows.variants(selected.group,QuickShareChoice(false,"unknown")).single {it.selected}.format.id)
        assertEquals(QuickShareVideoRows.tierKey(null),selected.key)
    }
}
