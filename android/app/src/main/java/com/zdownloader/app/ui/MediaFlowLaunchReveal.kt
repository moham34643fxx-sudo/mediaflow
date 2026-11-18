package com.zdownloader.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zdownloader.app.R

private const val WordmarkWidth = 192f
private const val WordmarkHeight = 40f
private const val LetterStepMillis = 44
private const val LetterFadeMillis = 90
private const val RevealMillis = 8 * LetterStepMillis + LetterFadeMillis
private const val ExitMillis = 120
private const val FirstFrameAlpha = .32f
private val LaunchInk = Color(0xFFF0F5FC)
private val LaunchAccent = Color(0xFF76E5DF)

@Composable
internal fun MediaFlowLaunchReveal(
    start: Boolean = true,
    onBrandVisible: () -> Unit = {},
    finished: () -> Unit
) {
    val context = LocalContext.current
    val motion = LocalFlowMotionEnabled.current && remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val brandVisible by rememberUpdatedState(onBrandVisible)
    val complete by rememberUpdatedState(finished)
    val entrance = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }

    LaunchedEffect(start, motion) {
        if (!start) return@LaunchedEffect
        if (motion) {
            entrance.animateTo(RevealMillis.toFloat(), tween(RevealMillis, easing = LinearEasing))
            // Submit the complete wordmark before Home's initial composition can block drawing.
            withFrameNanos { }
            brandVisible()
            exit.animateTo(1f, tween(ExitMillis, easing = FastOutSlowInEasing))
        } else {
            // A settled frame, without a timed hold or transitions.
            withFrameNanos { }
        }
        complete()
    }
    LaunchBrandSurface(
        elapsedMillis = { if (motion) entrance.value else RevealMillis.toFloat() },
        exitProgress = { exit.value }
    )
}

@Composable
private fun LaunchBrandSurface(elapsedMillis: () -> Float, exitProgress: () -> Float) {
    val background = colorResource(R.color.mediaflow_launch_background)
    val light = colorResource(R.color.mediaflow_launch_light)
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("brand_launch_reveal")
            .graphicsLayer { alpha = 1f - exitProgress() }
            .background(background)
            .drawWithCache {
                val radius = 92.dp.toPx()
                val ambient = Brush.radialGradient(listOf(light, background), Offset(size.width / 2, size.height / 2), radius)
                onDrawBehind { drawCircle(ambient, radius) }
            },
        contentAlignment = Alignment.Center
    ) {
        val width = minOf(WordmarkWidth.dp, maxWidth)
        LaunchEmblem(elapsedMillis)
        LaunchWordmark(
            Modifier.offset(y = 56.dp).size(width, width * (WordmarkHeight / WordmarkWidth)),
            elapsedMillis
        )
    }
}

@Composable
private fun LaunchEmblem(elapsedMillis: () -> Float) {
    val emblem = painterResource(R.drawable.mediaflow_mark)
    Box(Modifier.offset(y = (-23).dp).size(100.dp).testTag("brand_launch_mark").drawWithCache {
        val art = Size(size.minDimension, size.minDimension)
        onDrawBehind {
            with(emblem) { draw(art, alpha = letterAlpha(elapsedMillis(), 0)) }
        }
    })
}

@Composable
private fun LaunchWordmark(modifier: Modifier, elapsedMillis: () -> Float) {
    val firstLetter = painterResource(R.drawable.mediaflow_launch_first_letter)
    val remainingLetters = remember { launchLetterPaths() }
    Box(modifier.testTag("brand_launch_name")
        .semantics { contentDescription = "MEDIAFLOW" }
        .drawWithCache {
            val viewportScale = size.width / WordmarkWidth
            onDrawBehind {
                val elapsed = elapsedMillis()
                scale(viewportScale, viewportScale, pivot = Offset.Zero) {
                    for (index in 0..remainingLetters.size) {
                        val alpha = letterAlpha(elapsed, index)
                        if (alpha <= 0f) break
                        if (index == 0) {
                            with(firstLetter) { draw(Size(WordmarkWidth, WordmarkHeight), alpha) }
                        } else {
                            val ink = if (index < 5) LaunchInk else LaunchAccent
                            drawPath(remainingLetters[index - 1], ink, alpha = alpha)
                        }
                    }
                }
            }
        })
}

private fun letterAlpha(elapsed: Float, index: Int): Float {
    val progress = ((elapsed - index * LetterStepMillis) / LetterFadeMillis).coerceIn(0f, 1f)
    val initialAlpha = if (index == 0) FirstFrameAlpha else 0f
    return initialAlpha + (1f - initialAlpha) * FastOutSlowInEasing.transform(progress)
}
