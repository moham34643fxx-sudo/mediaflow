package com.zdownloader.app.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zdownloader.app.R
import com.zdownloader.app.data.MediaPackageMetadata
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.qualityLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zdownloader.app.ui.spectrumStringResource as text

/**
 * Explicit media package metadata JSON sidecar export through Android's Create Document UI.
 * Exports a structured sidecar containing media origin, format parameters, codecs, and durations.
 */
@Composable internal fun MediaPackageExport(
    media: Media,
    selection: Selection?,
    title: String,
    onError: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val destination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val meta = MediaPackageMetadata(
                            title = title,
                            sourceUrl = media.url,
                            sourcePlatform = media.source,
                            uploader = media.uploader,
                            durationSeconds = media.duration,
                            formatId = selection?.primary?.id ?: "unknown",
                            resolution = selection?.primary?.let { qualityLabel(it)?.let { h -> "${h}p" } },
                            videoCodec = selection?.primary?.videoCodec,
                            audioCodec = selection?.secondary?.audioCodec ?: selection?.primary?.audioCodec,
                            containerExtension = selection?.output ?: "mp4"
                        )
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(meta.toJson().toString(2).toByteArray(Charsets.UTF_8))
                            stream.flush()
                        } ?: throw java.io.IOException("Unable to open destination stream")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onError()
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                } finally {
                    busy = false
                }
            }
        }
    }

    TextButton(
        onClick = {
            if (!busy) {
                val filename = MediaPackageMetadata.sidecarFilename(title)
                destination.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, filename)
                )
            }
        },
        enabled = !busy,
        modifier = Modifier.testTag("save_media_package")
    ) {
        Text(text(R.string.advanced_save_metadata))
    }
}
