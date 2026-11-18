package com.zdownloader.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.zdownloader.app.ui.FlowAlertDialog as AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R
import com.zdownloader.app.data.DuplicateReview
import com.zdownloader.app.ui.spectrumStringResource as text

@Composable internal fun DuplicateDialog(review:DuplicateReview,again:()->Unit,cancel:()->Unit,open:((com.zdownloader.app.data.DownloadMatch)->Unit)?=null) {
    val context=LocalContext.current
    AlertDialog(onDismissRequest=cancel,modifier=Modifier.testTag("duplicate_confirmation"),title={Text(text(R.string.duplicate_title))},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(text(if(review.completed.isNotEmpty()) R.string.duplicate_completed else R.string.duplicate_active))
        Text(text(R.string.duplicate_quality_hint),style=MaterialTheme.typography.bodySmall)
        review.open?.let {row->TextButton({
            if(open!=null) {
                open(row)
            } else {
                val uri=row.uri?.let(Uri::parse)
                if(uri!=null) runCatching {context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,context.contentResolver.getType(uri) ?: "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure {android.widget.Toast.makeText(context,R.string.file_missing,android.widget.Toast.LENGTH_LONG).show()}
            }
            cancel()
        },Modifier.testTag("duplicate_open")) {Text(text(R.string.duplicate_open))}}
    }},confirmButton={TextButton(again,Modifier.testTag("duplicate_again")) {Text(text(R.string.duplicate_again))}},dismissButton={TextButton(cancel) {Text(text(R.string.cancel))}})
}
