package com.zdownloader.app.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.ui.FlowIcons
import com.zdownloader.app.ui.spectrumStringResource as text

/** Only completed download records enter this destination; device browsing has its own route. */
@Composable internal fun DownloadedMediaLibrary(connection: PlaybackConnection, downloads: List<DownloadEntity>, openLocal: () -> Unit, extractAudio: (String) -> Unit) {
    Column(Modifier.fillMaxSize().testTag("standalone_library")) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            val openLabel=text(R.string.local_open)
            val localAction:@Composable ()->Unit={
                TextButton(openLocal,Modifier.heightIn(min=48.dp).testTag("library_open_file").semantics {contentDescription=openLabel}) {
                    Icon(FlowIcons.Folder,null,Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text(R.string.library_device_media_action))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth/LocalDensity.current.fontScale<280.dp) {
                    Column {
                        Text(text(R.string.library),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
                        localAction()
                    }
                } else Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(text(R.string.library),Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.headlineSmall)
                    localAction()
                }
            }
            Text(text(R.string.local_download_library_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f)) {SmartMediaBrowser(connection,MediaBrowserSource.Downloads(downloads),extractAudio)}
    }
}
