package com.zdownloader.app.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.ui.spectrumStringResource as text

private const val FFMPEG_SOURCE_URL =
    "https://github.com/arthenica/FFmpeg/tree/34277e12e80031c7f89494ba543684bc1dd0be8f"
private const val FFMPEG_KIT_SOURCE_URL =
    "https://github.com/JamaisMagic/ffmpeg-kit-16KB/tree/c7ca204219d549f563799bacbbdfeb19e31530f2"

@Composable
private fun AboutWaves() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (layer in 0..1) {
            val y = h * (0.86f + layer * 0.05f)
            val path = Path().apply {
                moveTo(0f, y)
                cubicTo(w * .35f, y - h * .16f, w * .62f, y + h * .12f, w, y - h * .02f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path, if (layer == 0) Color(0xFF91E3D1).copy(alpha = .045f)
                 else Color(0xFFACC9EB).copy(alpha = .035f))
        }
    }
}

@Composable
internal fun AboutScreen(back: () -> Unit, privacy: () -> Unit, licenses: () -> Unit) {
    val brandGradient=flowAccentGradient()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val reveal by animateFloatAsState(if (entered||!LocalFlowMotionEnabled.current) 1f else 0f, tween(FlowMotion.Reveal), label = "aboutReveal")
    val offset = with(LocalDensity.current) { 6.dp.toPx() }
    Box(Modifier.fillMaxSize().testTag("information_about")) {
        AboutWaves()
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowIconButton(FlowIcons.Back,text(R.string.back),back,Modifier.testTag("information_back"))
                Text(
                    text(R.string.about),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .testTag("about_scroll")
                    .graphicsLayer { alpha = reveal; translationY = (1f - reveal) * offset },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(24.dp))
                    // User-approved glyph crop; title stays accessible and appears only once.
                    Image(
                        painterResource(R.drawable.mediaflow_mark),
                        contentDescription = null,
                        modifier = Modifier.width(180.dp).height(135.dp).testTag("about_logo")
                    )
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val approxMaxWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 48).coerceAtMost(512).coerceAtLeast(200)
                    val titleSize = minOf(40f, approxMaxWidth / (5.8f * LocalDensity.current.fontScale)).coerceAtLeast(18f).sp
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append("Media")
                            }
                            withStyle(SpanStyle(brush = brandGradient)) {
                                append("Flow")
                            }
                        },
                        Modifier.testTag("about_title").semantics { heading() },
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = titleSize,
                            lineHeight = titleSize * 1.2f,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            textDirection = TextDirection.Ltr
                        ),
                        textAlign = TextAlign.Center
                    )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text(R.string.about_tagline),
                        Modifier.testTag("about_subtitle"),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text(R.string.version_display, BuildConfig.VERSION_NAME),
                            Modifier.padding(horizontal = 18.dp, vertical = 8.dp).testTag("about_version"),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text(R.string.developer_attribution),
                        Modifier.testTag("about_developer"),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    Surface(
                        onClick = privacy,
                        modifier = Modifier.fillMaxWidth().testTag("privacy_information"),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                    ) {
                        Row(
                            Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            FlowIconTile(FlowIcons.Privacy,size=48.dp,iconSize=24.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text(R.string.privacy_information), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text(R.string.about_privacy_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                FlowIcons.Next,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    NativeLibraryNotices(licenses)
                    Text(
                        text(R.string.about_copyright),
                        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp).testTag("about_copyright"),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeLibraryNotices(licenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val openFailed = text(R.string.about_source_open_failed)
    fun openSource(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, openFailed, Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(text(R.string.about_ffmpeg_notice), style = MaterialTheme.typography.bodyMedium)
        Text(text(R.string.about_ffmpeg_copyright), style = MaterialTheme.typography.bodySmall)
        Text(
            text(R.string.about_lame_notice),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = licenses, modifier = Modifier.testTag("about_native_licenses")) {
            Text(text(R.string.about_third_party_notices))
        }
        TextButton(onClick = { openSource(FFMPEG_SOURCE_URL) }, modifier = Modifier.testTag("about_ffmpeg_source")) {
            Text(text(R.string.about_ffmpeg_source))
        }
        TextButton(onClick = { openSource(FFMPEG_KIT_SOURCE_URL) }) {
            Text(text(R.string.about_ffmpeg_kit_source))
        }
    }
}
