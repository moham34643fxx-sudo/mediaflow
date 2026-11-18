package com.zdownloader.app.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zdownloader.app.R
import com.zdownloader.app.domain.AudioPopupMetadata
import com.zdownloader.app.ui.*
import com.zdownloader.app.ui.spectrumStringResource as text

internal data class AudioPopupActions(val close:()->Unit,val full:()->Unit,val floating:()->Unit,val background:()->Unit,val stop:()->Unit)

/** Compact local-audio preview. Dismissing a surface never owns the playback lifetime. */
@Composable internal fun AudioPopupScreen(connection:PlaybackConnection,requestId:String?,actions:AudioPopupActions) {
    val state by connection.state.collectAsStateWithLifecycle()
    val loading=requestId!=null&&!connection.repository.externalRequestHandled(requestId)
    val media=state.current.takeUnless {loading||state.error!=null}
    Dialog(onDismissRequest=actions.close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(12.dp).widthIn(max=344.dp).fillMaxWidth().testTag("compact_audio_player"),
            shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)),shadowElevation=8.dp) {
            Column(Modifier.padding(horizontal=8.dp,vertical=6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled=media!=null,onClickLabel=text(R.string.audio_popup_full_player),onClick=actions.full)
                        .padding(4.dp).testTag("compact_audio_full"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        PlayerArtwork(media,Modifier.size(36.dp)) {
                            Image(painterResource(R.drawable.ic_brand_mark),null,Modifier.size(28.dp).testTag("compact_audio_artwork_fallback"))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if(loading) text(R.string.preparing_audio) else AudioPopupMetadata.label(media?.title) ?: text(R.string.audio_popup_untitled),
                                maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,modifier=Modifier.testTag("compact_audio_title"))
                            AudioPopupMetadata.label(media?.artist)?.let {Text(it,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("compact_audio_artist"))}
                        }
                    }
                    IconButton(connection::toggle,Modifier.size(48.dp).testTag("compact_audio_toggle"),enabled=media!=null) {
                        if(loading||state.buffering) FlowLoadingIndicator(compact=true)
                        else Icon(if(state.playing) FlowIcons.Pause else FlowIcons.Play,text(if(state.playing) R.string.player_pause else R.string.player_play),tint=MaterialTheme.colorScheme.primary)
                    }
                    IconButton(actions.close,Modifier.size(48.dp).testTag("compact_audio_close")) {Icon(FlowIcons.Close,text(R.string.audio_popup_dismiss),Modifier.size(18.dp))}
                }
                LinearProgressIndicator(progress={(state.position.toFloat()/state.duration.coerceAtLeast(1)).coerceIn(0f,1f)},
                    modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp).height(2.dp),drawStopIndicator={})
                state.error?.let {Text(text(it),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp).testTag("compact_audio_error"))}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(actions.background,Modifier.weight(1f).testTag("compact_audio_background"),enabled=media!=null) {
                        Text(text(R.string.audio_popup_background),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    IconButton(actions.floating,Modifier.size(48.dp).testTag("compact_audio_floating"),enabled=media!=null) {
                        Icon(Icons.Outlined.PictureInPictureAlt,text(R.string.audio_popup_floating_title),Modifier.size(20.dp))
                    }
                    IconButton(actions.stop,Modifier.size(48.dp).testTag("compact_audio_stop")) {Icon(Icons.Outlined.Stop,text(R.string.audio_popup_stop),Modifier.size(20.dp))}
                }
            }
        }
    }
}
