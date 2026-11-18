package com.zdownloader.app.playback

import android.Manifest
import com.zdownloader.app.data.LocalMediaKind

/** Request only the category the user chose; selected visual access remains a valid result. */
internal fun LocalMediaKind.readPermission(sdk: Int): String = if(sdk < 33) {
    Manifest.permission.READ_EXTERNAL_STORAGE
} else when(this) {
    LocalMediaKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
    LocalMediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
    LocalMediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
}

internal fun LocalMediaKind.readPermissions(sdk: Int): Array<String> =
    if(sdk >= 34 && this != LocalMediaKind.AUDIO) {
        arrayOf(readPermission(sdk), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else arrayOf(readPermission(sdk))
