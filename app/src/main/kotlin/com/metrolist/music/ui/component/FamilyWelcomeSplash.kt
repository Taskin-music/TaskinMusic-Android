/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-end personalized welcome splash overlay with dynamic ambient aurora canvas,
 * animated equalizer motion, glassmorphic card design, and native haptic feedback.
 */
@Composable
fun FamilyWelcomeSplash(
    memberName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isVisible by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {}
    }

    BackHandler(enabled = isVisible) {
        isVisible = false
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(2800)
            isVisible = false
        } else {
            delay(350)
            onDismiss()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "welcome_infinite")

    // Ambient Aurora pulse and movement
    val auroraPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aurora_phase",
    )

    // Pulse scale for emblem halo
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    // Name shimmer animation
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "name_shimmer",
    )

    // Equalizer wave bar animations
    val eqBar1 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq1",
    )
    val eqBar2 by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq2",
    )
    val eqBar3 by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 34f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq3",
    )
    val eqBar4 by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq4",
    )
    val eqBar5 by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq5",
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(450, easing = LinearOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.90f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
        exit = fadeOut(tween(350, easing = FastOutLinearInEasing)) +
            scaleOut(targetScale = 1.05f, animationSpec = tween(350, easing = FastOutLinearInEasing)),
        modifier = modifier,
    ) {
        val primaryColor = MaterialTheme.colorScheme.primary
        val tertiaryColor = MaterialTheme.colorScheme.tertiary
        val surfaceColor = MaterialTheme.colorScheme.surface
        val surfaceContainerLowest = MaterialTheme.colorScheme.surfaceContainerLowest

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    isVisible = false
                },
            contentAlignment = Alignment.Center,
        ) {
            // Dynamic Ambient Aurora Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Deep atmospheric gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            surfaceColor,
                            surfaceContainerLowest,
                            surfaceColor,
                        ),
                    ),
                )

                // Aurora Glow Orb 1 (Primary - Floating upper center)
                val orb1X = w * 0.5f + cos(auroraPhase) * (w * 0.12f)
                val orb1Y = h * 0.42f + sin(auroraPhase) * (h * 0.08f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.38f),
                            primaryColor.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                        center = Offset(orb1X, orb1Y),
                        radius = w * 0.65f,
                    ),
                )

                // Aurora Glow Orb 2 (Tertiary - Floating lower right)
                val orb2X = w * 0.65f - sin(auroraPhase * 1.2f) * (w * 0.14f)
                val orb2Y = h * 0.55f + cos(auroraPhase * 1.2f) * (h * 0.07f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            tertiaryColor.copy(alpha = 0.28f),
                            tertiaryColor.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        center = Offset(orb2X, orb2Y),
                        radius = w * 0.55f,
                    ),
                )
            }

            // Glassmorphic Center Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f),
                tonalElevation = 8.dp,
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.55f),
                            tertiaryColor.copy(alpha = 0.30f),
                            Color.Transparent,
                        ),
                    ),
                ),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                ) {
                    // Logo Box with Breathing Ambient Ring
                    Box(
                        contentAlignment = Alignment.Center,
                    ) {
                        // Ambient radial glow behind emblem
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .scale(pulseScale)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            primaryColor.copy(alpha = 0.35f),
                                            tertiaryColor.copy(alpha = 0.15f),
                                            Color.Transparent,
                                        ),
                                    ),
                                    shape = CircleShape,
                                ),
                        )

                        // Frosted Logo Tile
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            ),
                            tonalElevation = 4.dp,
                            modifier = Modifier.size(86.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.app_logo),
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(50.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Brand Pill with Audio Indicator
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.music_note),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Taşkın Music",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                letterSpacing = 0.4.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Greeting Header: "Hoşgeldin,"
                    Text(
                        text = "Hoşgeldin,",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        letterSpacing = 1.sp,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Family Member Name with Dynamic Shimmer Gradient
                    val nameBrush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor,
                            tertiaryColor,
                            primaryColor,
                        ),
                        start = Offset(shimmerOffset - 250f, 0f),
                        end = Offset(shimmerOffset + 250f, 0f),
                    )

                    Text(
                        text = memberName,
                        style = MaterialTheme.typography.headlineLarge.copy(brush = nameBrush),
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Music Waveform Visualizer Bars
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(34.dp),
                    ) {
                        listOf(eqBar1, eqBar2, eqBar3, eqBar4, eqBar5).forEach { heightValue ->
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(heightValue.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                primaryColor,
                                                tertiaryColor,
                                            ),
                                        ),
                                    ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tagline
                    Text(
                        text = "Keyifli dinlemeler dileriz ✨",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(26.dp))

                    // Interactive Dismiss Pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.expand_more),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Başlamak için dokunun",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}
