package com.zdownloader.app.data

/** Device-library categories, deliberately separate from playback's audio/video model. */
internal enum class LocalMediaKind {
    VIDEO, AUDIO, IMAGE;

    fun accepts(mime: String?): Boolean = when(this) {
        VIDEO -> mime?.startsWith("video/") == true
        AUDIO -> mime?.startsWith("audio/") == true || mime == "application/ogg"
        IMAGE -> mime?.startsWith("image/") == true
    }
}
