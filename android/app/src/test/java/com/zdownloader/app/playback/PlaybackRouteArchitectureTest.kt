package com.zdownloader.app.playback

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PlaybackRouteArchitectureTest {
    private val androidNamespace="http://schemas.android.com/apk/res/android"

    private fun manifest()=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun Element.android(name:String)=getAttributeNS(androidNamespace,name)

    private fun elements(document:org.w3c.dom.Document,name:String):List<Element> =
        (0 until document.getElementsByTagName(name).length).map {document.getElementsByTagName(name).item(it) as Element}

    @Test fun obsoletePrivateCompactAudioRouteIsNotPackagedOrRegistered() {
        val document=manifest()
        assertFalse(elements(document,"activity").any {it.android("name")==".playback.InAppAudioPreviewActivity"})
        assertFalse(File("src/main/java/com/zdownloader/app/playback/InAppAudioPreviewActivity.kt").exists())
        assertFalse(File("src/main/java/com/zdownloader/app/playback/AudioPopupScreen.kt").exists())
    }

    @Test fun externalAudioResolverCompatibilityAndSharedPlayerServiceRemainRegistered() {
        val document=manifest()
        val audioAlias=elements(document,"activity-alias").single {it.android("name")==".playback.AudioPopupActivity"}
        assertEquals(".playback.ExternalAudioOpenActivity",audioAlias.android("targetActivity"))
        assertEquals("true",audioAlias.android("exported"))
        val dataNodes=audioAlias.getElementsByTagName("data")
        val audioMimeTypes=(0 until dataNodes.length).map {(dataNodes.item(it) as Element).android("mimeType")}.toSet()
        assertTrue(audioMimeTypes.contains("audio/mpeg"))
        assertTrue(audioMimeTypes.contains("audio/x-matroska"))

        val activities=elements(document,"activity")
        assertTrue(activities.any {it.android("name")==".playback.ExternalAudioOpenActivity"&&it.android("exported")=="false"})
        assertTrue(activities.any {it.android("name")==".playback.PlayerActivity"})
        assertTrue(elements(document,"service").any {it.android("name")==".playback.MediaPlaybackService"})
    }
}
