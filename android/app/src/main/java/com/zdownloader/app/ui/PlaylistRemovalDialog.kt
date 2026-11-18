package com.zdownloader.app.ui

import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zdownloader.app.R
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun PlaylistRemovalDialog(remove:Boolean,dismiss:()->Unit,confirm:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,
        title={Text(text(if(remove) R.string.pl_remove_cancelled else R.string.pl_cancel_all))},
        text={Text(text(if(remove) R.string.pl_remove_cancelled_confirm else R.string.pl_cancel_confirm))},
        confirmButton={TextButton(confirm,Modifier.testTag(if(remove) "playlist_confirm_remove" else "playlist_confirm_cancel")) {
            Text(text(if(remove) R.string.pl_remove_cancelled else R.string.pl_cancel_all))
        }},
        dismissButton={TextButton(dismiss,Modifier.testTag("playlist_dismiss_confirmation")) {Text(text(R.string.cancel))}})
}
