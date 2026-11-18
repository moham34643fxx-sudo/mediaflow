package com.zdownloader.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zdownloader.app.BuildConfig
import com.zdownloader.app.R
import com.zdownloader.app.ui.spectrumStringResource as stringResource

@Composable
internal fun WelcomeScreen(
    versionName: String = BuildConfig.VERSION_NAME,
    onComplete: () -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) } // 0, 1, 2

    // Back navigation: step backward on 1 and 2, exit on 0 (Step 1)
    BackHandler(enabled = step > 0) {
        step--
    }

    val direction=if(LocalSpectrumContext.current.resources.configuration.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(mediaFlowBackground())
                .testTag("welcome_screen")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            ) {
                // Top Progress Indicator
                WelcomeProgressHeader(
                    currentStep = step,
                    totalSteps = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 12.dp, start = 24.dp, end = 24.dp)
                )

                // Scrollable Content
                val stepScroll = rememberScrollState()
                LaunchedEffect(step) { stepScroll.scrollTo(0) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .verticalScroll(stepScroll)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val motion = LocalFlowMotionEnabled.current
                        AnimatedContent(
                            targetState = step,
                            contentAlignment = Alignment.Center,
                            transitionSpec = {
                                val transform = if (!motion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else if (targetState > initialState) {
                                    (fadeIn(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) +
                                        slideInHorizontally(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) { -it / 7 })
                                        .togetherWith(
                                            fadeOut(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) +
                                                slideOutHorizontally(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) { it / 7 }
                                        )
                                } else {
                                    (fadeIn(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) +
                                        slideInHorizontally(tween(FlowMotion.Scene, easing = FastOutSlowInEasing)) { it / 7 })
                                        .togetherWith(
                                            fadeOut(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) +
                                                slideOutHorizontally(tween(FlowMotion.Exit, easing = FastOutSlowInEasing)) { -it / 7 }
                                        )
                                }
                                transform.using(SizeTransform(clip = false))
                            },
                            label = "welcomeStepAnimation"
                        ) { currentStep ->
                            when (currentStep) {
                                0 -> WelcomeStep1()
                                1 -> WelcomeStep2()
                                2 -> WelcomeStep3(versionName = versionName)
                            }
                        }
                    }
                }

                // Bottom Action Bar (No Skip, No Close)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PrimaryAction(
                        onClick = {
                            if (step < 2) {
                                step++
                            } else {
                                onComplete()
                            }
                        },
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .testTag(if (step == 2) "welcome_done_button" else "welcome_next_button")
                    ) {
                        Text(
                            text = stringResource(if (step == 2) R.string.welcome_done else R.string.welcome_next),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = if (step == 2) FlowIcons.Success else FlowIcons.Next,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeProgressHeader(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.testTag("welcome_step_indicator"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until totalSteps) {
                val isActive = i == currentStep
                val isCompleted = i < currentStep
                val motion = LocalFlowMotionEnabled.current
                val width by animateDpAsState(
                    targetValue = if (isActive) 32.dp else 12.dp,
                    animationSpec = tween(if (motion) FlowMotion.Reveal else 0),
                    label = "stepIndicatorWidth"
                )
                val color by animateColorAsState(
                    targetValue = when {
                        isActive -> MaterialTheme.colorScheme.primary
                        isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    },
                    animationSpec = tween(if (motion) FlowMotion.Fast else 0),
                    label = "stepIndicatorColor"
                )
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(width)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.welcome_step_indicator, currentStep + 1, totalSteps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun WelcomeStep1() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("welcome_step1_content"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier
                .padding(vertical = 16.dp)
                .size(108.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.mediaflow_mark),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_step1_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_step1_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )

        Spacer(Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = FlowIcons.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.welcome_step1_notice),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep2() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("welcome_step2_content"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier
                .padding(vertical = 16.dp)
                .size(108.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = FlowIcons.Browser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_step2_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_step2_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}

@Composable
private fun WelcomeStep3(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("welcome_step3_content"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier
                .padding(vertical = 16.dp)
                .size(108.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = FlowIcons.Updates,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.welcome_step3_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            modifier = Modifier.testTag("welcome_version_badge")
        ) {
            Text(
                text = stringResource(R.string.welcome_step3_version, versionName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FlowIconTile(
                        icon = FlowIcons.Tune,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 38.dp,
                        iconSize = 20.dp
                    )
                    Text(
                        text = stringResource(R.string.welcome_step3_bullet_speed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    FlowIconTile(
                        icon = FlowIcons.Success,
                        tint = flowSuccessColor(),
                        size = 38.dp,
                        iconSize = 20.dp
                    )
                    Text(
                        text = stringResource(R.string.welcome_step3_bullet_stability),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
