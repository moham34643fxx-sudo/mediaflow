package com.zdownloader.app.playback

import androidx.media3.common.Player

/** Same transport contract for system, Bluetooth, wearables and other external UIDs.
 * Trust labels are not a reliable indicator for legitimate companion applications. */
internal object ExternalControllerPolicy {
    val commands=setOf(
        Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,Player.COMMAND_GET_TRACKS,Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        Player.COMMAND_GET_VOLUME,Player.COMMAND_GET_DEVICE_VOLUME
    )
}
