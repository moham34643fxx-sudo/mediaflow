package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class AudioPopupMetadataTest {
    @Test fun providerAndEmbeddedTechnicalPathsNeverBecomeVisibleLabels() {
        for(candidate in listOf(null,"","   ","<unknown>","content://media/external/audio/14","FILE:///storage/audio.mp3",
            "https://example.com/private", "/storage/emulated/0/recording.mp3","C:\\media\\recording.mp3","Track content://provider/item"))
            assertNull(AudioPopupMetadata.label(candidate))
    }

    @Test fun arabicAndLongHumanReadableTitlesRemainTextNotPaths() {
        assertEquals("الدرس السادس — الحفظ والاسترجاع",AudioPopupMetadata.label("\nالدرس السادس — الحفظ والاسترجاع\t"))
        assertEquals("Artist",AudioPopupMetadata.label("Artist"))
        assertEquals(512,AudioPopupMetadata.label("تسجيل ".repeat(200))!!.length)
    }
}
