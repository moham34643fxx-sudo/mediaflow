package com.zdownloader.app.playback

import android.Manifest
import com.zdownloader.app.data.LocalMediaKind
import org.junit.Assert.*
import org.junit.Test

class LocalMediaAccessTest {
    @Test fun legacyDevicesUseOnlyTheExistingReadPermission() {
        for(sdk in listOf(24,28,29,32)) for(kind in LocalMediaKind.entries) {
            assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),kind.readPermissions(sdk))
        }
    }

    @Test fun android13RequestsOnlyTheChosenCategory() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_VIDEO),LocalMediaKind.VIDEO.readPermissions(33))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_AUDIO),LocalMediaKind.AUDIO.readPermissions(33))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES),LocalMediaKind.IMAGE.readPermissions(33))
    }

    @Test fun modernVisualCategoriesAllowSelectedAccessWithoutRequestingTheOtherCategory() {
        for(sdk in listOf(34,35,36)) {
            assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),LocalMediaKind.VIDEO.readPermissions(sdk))
            assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),LocalMediaKind.IMAGE.readPermissions(sdk))
            assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_AUDIO),LocalMediaKind.AUDIO.readPermissions(sdk))
        }
    }

    @Test fun imageFilesCannotMatchAudioOrVideoCategories() {
        for(mime in listOf("image/jpeg","image/png","image/webp","image/heif","image/avif")) {
            assertTrue(LocalMediaKind.IMAGE.accepts(mime))
            assertFalse(LocalMediaKind.AUDIO.accepts(mime))
            assertFalse(LocalMediaKind.VIDEO.accepts(mime))
        }
    }

    @Test fun audioAndVideoRemainDistinctIncludingOggAudio() {
        assertTrue(LocalMediaKind.AUDIO.accepts("audio/mpeg"))
        assertTrue(LocalMediaKind.AUDIO.accepts("application/ogg"))
        assertTrue(LocalMediaKind.VIDEO.accepts("video/webm"))
        assertFalse(LocalMediaKind.IMAGE.accepts("video/webm"))
        assertFalse(LocalMediaKind.VIDEO.accepts("audio/ogg"))
    }

    @Test fun unknownFilesAndDirectoriesDoNotBecomePlayableMedia() {
        for(mime in listOf(null,"","application/pdf","vnd.android.document/directory")) {
            assertFalse(LocalMediaKind.entries.any {it.accepts(mime)})
        }
    }
}
