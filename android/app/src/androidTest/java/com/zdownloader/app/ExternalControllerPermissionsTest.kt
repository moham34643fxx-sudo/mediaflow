package com.zdownloader.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.playback.PlaybackRuntime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExternalControllerPermissionsTest {
    @Test fun separateUidKeepsTransportButCannotChangeQueueOrPrivilegedSettings()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        withContext(Dispatchers.Main) {app.playback.attach();app.playback.connect()}
        try {
            val probe=Uri.parse("content://${instrument.context.packageName}.external-media-controller")
            val result=app.contentResolver.call(probe,"modernPermissions",null,Bundle().apply {putString("package",app.packageName)})!!
            assertNotEquals(android.os.Process.myUid(),result.getInt("uid"))
            assertEquals(app.packageManager.getApplicationInfo(instrument.context.packageName,0).uid,result.getInt("uid"))
            assertTrue(result.getBoolean("command_${Player.COMMAND_PLAY_PAUSE}"))
            assertTrue(result.getBoolean("command_${Player.COMMAND_GET_METADATA}"))
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,result.getInt("custom_result"))
            assertTrue("Unauthorized custom Stop must not stop MediaFlow's session",PlaybackRuntime.active)
            for(command in listOf(Player.COMMAND_SET_MEDIA_ITEM,Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_PLAYLIST_METADATA,Player.COMMAND_SET_VOLUME))
                assertFalse("$command must not be granted to a different UID",result.getBoolean("command_$command"))
            // Media3 1.11.1 always adds local controller RELEASE; it cannot release the remote player.
            assertTrue(result.getBoolean("released_connection"))
            withContext(Dispatchers.Main) {assertTrue(app.playback.connect().isConnected)}
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val evidence=java.io.File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            val report=org.json.JSONObject()
            for(key in result.keySet()) report.put(key,result.get(key))
            report.put("remote_session_survived_controller_release",PlaybackRuntime.active)
            java.io.File(evidence,"external-controller-permissions.json").writeText(report.toString(2))
        } finally {withContext(Dispatchers.Main) {app.playback.stopAudio();app.playback.detach()}}
    }
}
