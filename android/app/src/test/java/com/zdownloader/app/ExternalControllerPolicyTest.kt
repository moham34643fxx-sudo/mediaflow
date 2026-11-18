package com.zdownloader.app

import androidx.media3.common.Player
import com.zdownloader.app.playback.ExternalControllerPolicy
import org.junit.Assert.*
import org.junit.Test

class ExternalControllerPolicyTest {
    @Test fun externalClientsKeepTransportAndMetadataButNotPrivilegedMutation() {
        val allowed=ExternalControllerPolicy.commands
        for(command in listOf(Player.COMMAND_PLAY_PAUSE,Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,Player.COMMAND_GET_TIMELINE)) assertTrue("missing $command",command in allowed)
        for(command in listOf(Player.COMMAND_CHANGE_MEDIA_ITEMS,Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_SET_PLAYLIST_METADATA,Player.COMMAND_SET_VOLUME,Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_SPEED_AND_PITCH,Player.COMMAND_SET_VIDEO_SURFACE,Player.COMMAND_RELEASE))
            assertFalse("privileged $command",command in allowed)
    }
}
