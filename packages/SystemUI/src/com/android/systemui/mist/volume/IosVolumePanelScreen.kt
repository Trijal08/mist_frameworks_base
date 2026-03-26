/*
 * Copyright (C) 2026 MistOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.mist.volume

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Color palette ────────────────────────────────────────────────────────────
private val IosPillShape       = RoundedCornerShape(percent = 50)
private val IosCardShape       = RoundedCornerShape(percent = 50)
private val IosRingerShape     = RoundedCornerShape(percent = 50)

private class IosThemeColors(val isDark: Boolean) {
    val trackBg         = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val normalFill      = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1C1C1E)
    val silentFill      = Color(0xFFFF453A)
    val vibrateFill     = Color(0xFF32D74B)
    val ringFill        = Color(0xFFFF9F0A)
    val alarmFill       = Color(0xFFFF6B00)
    val divider         = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val iconTint        = if (isDark) Color.White else Color(0xFF1C1C1E)
    val labelColor      = if (isDark) Color.White.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.60f)
    
    // Smoothly calculate what color the icon inside the slider should be based on its fill percentage
    fun getDynamicIconTint(fraction: Float, isNormalColor: Boolean): Color {
        val isCovered = fraction > 0.15f
        return when {
            isCovered -> {
                // If it's normal volume fill and dark mode, the fill is White! So icon must be Black.
                if (isNormalColor && isDark) Color(0xFF1C1C1E) else Color.White
            }
            else -> {
                // Not covered. Just draw it against the track.
                if (isDark) Color(0xFF8E8E93) else Color(0xFF8E8E93)
            }
        }
    }
}

/**
 * Root Compose screen for the iOS-style volume panel.
 *
 * Layout:
 * ```
 * ┌──────────────────────┐  ← slide in from right
 * │   [Media Slider]     │  (always visible, compact)
 * │                      │
 * │   ↕ expand ↕         │
 * │   [Ring Slider]      │
 * │   [Alarm Slider]     │
 * │   [Call Slider]      │
 * │   ┌────────────────┐ │
 * │   │ 🔔 〜  🔊      │ │  ← ringer mode row
 * │   └────────────────┘ │
 * ```
 */
@Composable
fun IosVolumePanelScreen(
    dismissTrigger: Boolean,
    onExpansionChanged: (Boolean) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val vibrator = remember {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    val hasVibrator = remember { vibrator?.hasVibrator() == true }

    // ─── Volume state ──────────────────────────────────────────────────────
    fun vol(stream: Int): Float {
        val max = audioManager.getStreamMaxVolume(stream).toFloat()
        val cur = audioManager.getStreamVolume(stream).toFloat()
        return if (max > 0f) cur / max else 0f
    }

    var mediaVol    by remember { mutableFloatStateOf(vol(AudioManager.STREAM_MUSIC)) }
    var ringVol     by remember { mutableFloatStateOf(vol(AudioManager.STREAM_RING)) }
    var alarmVol    by remember { mutableFloatStateOf(vol(AudioManager.STREAM_ALARM)) }
    var callVol     by remember { mutableFloatStateOf(vol(AudioManager.STREAM_VOICE_CALL)) }
    var ringerMode  by remember { mutableIntStateOf(audioManager.ringerMode) }

    // ─── Expansion & dismiss state ─────────────────────────────────────────
    var expanded  by remember { mutableStateOf(false) }
    var visible   by remember { mutableStateOf(true) }

    // Trigger dismiss animation when plugin requests it
    LaunchedEffect(dismissTrigger) {
        if (dismissTrigger) {
            visible = false
            delay(350)
            onDismissed()
        }
    }

    // Notify plugin of expansion changes
    LaunchedEffect(expanded) { onExpansionChanged(expanded) }

    // ─── Broadcast listener ────────────────────────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> ringerMode = audioManager.ringerMode
                    "android.media.VOLUME_CHANGED_ACTION" -> {
                        mediaVol  = vol(AudioManager.STREAM_MUSIC)
                        ringVol   = vol(AudioManager.STREAM_RING)
                        alarmVol  = vol(AudioManager.STREAM_ALARM)
                        callVol   = vol(AudioManager.STREAM_VOICE_CALL)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ─── Helper — set stream volume ─────────────────────────────────────────
    fun setVol(stream: Int, fraction: Float) {
        val max = audioManager.getStreamMaxVolume(stream)
        val target = (fraction * max).toInt().coerceIn(0, max)
        scope.launch(Dispatchers.IO) {
            try { audioManager.setStreamVolume(stream, target, 0) } catch (_: Exception) {}
        }
    }

    // ─── Ringer cycle ──────────────────────────────────────────────────────
    fun cycleRingerMode() {
        val next = when (ringerMode) {
            AudioManager.RINGER_MODE_NORMAL  -> if (hasVibrator) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else                             -> AudioManager.RINGER_MODE_NORMAL
        }
        scope.launch(Dispatchers.IO) {
            try { audioManager.ringerModeInternal = next } catch (_: Exception) {}
        }
        ringerMode = next
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun setRingerMode(mode: Int) {
        scope.launch(Dispatchers.IO) {
            try { audioManager.ringerModeInternal = mode } catch (_: Exception) {}
        }
        ringerMode = mode
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ─── Panel width with spring animation on expand ────────────────────────
    val panelWidth by animateDpAsState(
        targetValue = if (expanded) 140.dp else 46.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "panelWidth"
    )

    val panelHeight by animateDpAsState(
        targetValue = if (expanded) 300.dp else 220.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "panelHeight"
    )

    // ─── Root: full-height overlay with the panel aligned to the right ──────
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            initialOffsetX = { it }
        ) + fadeIn(tween(200)),
        exit = slideOutHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            targetOffsetX = { it }
        ) + fadeOut(tween(200)),
    ) {
        Box(
            modifier = Modifier
                .height(panelHeight)
                .width(panelWidth)
                .padding(end = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val isDark = isSystemInDarkTheme()
            val colors = remember(isDark) { IosThemeColors(isDark) }
            IosVolumeCard(
                colors         = colors,
                expanded       = expanded,
                mediaVol       = mediaVol,
                ringVol        = ringVol,
                alarmVol       = alarmVol,
                callVol        = callVol,
                ringerMode     = ringerMode,
                hasVibrator    = hasVibrator,
                onExpandToggle = { expanded = !expanded },
                onCycleRinger  = { cycleRingerMode() },
                onSetRinger    = { setRingerMode(it) },
                onMediaDrag    = { f -> mediaVol = f;  setVol(AudioManager.STREAM_MUSIC,       f) },
                onRingDrag     = { f -> ringVol  = f;  setVol(AudioManager.STREAM_RING,        f) },
                onAlarmDrag    = { f -> alarmVol = f;  setVol(AudioManager.STREAM_ALARM,       f) },
                onCallDrag     = { f -> callVol  = f;  setVol(AudioManager.STREAM_VOICE_CALL,  f) },
                onInteractionStart = onInteractionStart,
                onInteractionEnd   = onInteractionEnd,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Volume card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IosVolumeCard(
    colors: IosThemeColors,
    expanded: Boolean,
    mediaVol: Float,
    ringVol: Float,
    alarmVol: Float,
    callVol: Float,
    ringerMode: Int,
    hasVibrator: Boolean,
    onExpandToggle: () -> Unit,
    onCycleRinger: () -> Unit,
    onSetRinger: (Int) -> Unit,
    onMediaDrag: (Float) -> Unit,
    onRingDrag: (Float) -> Unit,
    onAlarmDrag: (Float) -> Unit,
    onCallDrag: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val isRingSilenced = ringerMode == AudioManager.RINGER_MODE_SILENT
    val isRingVibrate  = ringerMode == AudioManager.RINGER_MODE_VIBRATE

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Expand / collapse toggle button ──────────────────────────────
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 24.dp)
                .clip(IosRingerShape)
                .background(Color.Transparent)
                .clickable { onExpandToggle() },
            contentAlignment = Alignment.Center,
        ) {
            val arrowIcon =
                if (expanded) R.drawable.ic_arrow_up_24dp else R.drawable.ic_arrow_down_24dp
            Icon(
                painter = painterResource(arrowIcon),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.labelColor,
                modifier = Modifier.size(16.dp),
            )
        }

        // ── Sliders Row ─────────────────────────────────────────────
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Expanded sliders pop out to the left
            if (expanded) {
                // Alarm
                IosVolumeSlider(
                    colors = colors,
                    fraction = alarmVol,
                    fillColor = colors.alarmFill,
                    iconRes = R.drawable.ic_volume_alarm,
                    onDrag = onAlarmDrag,
                    onLongPress = {},
                    onInteractionStart = onInteractionStart,
                    onInteractionEnd = onInteractionEnd,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                // Ring
                IosVolumeSlider(
                    colors = colors,
                    fraction = if (isRingSilenced || isRingVibrate) 0f else ringVol,
                    fillColor = when {
                        isRingSilenced -> colors.silentFill
                        isRingVibrate  -> colors.vibrateFill
                        else           -> colors.ringFill
                    },
                    iconRes = when {
                        isRingSilenced -> R.drawable.ic_volume_off
                        isRingVibrate  -> R.drawable.ic_volume_ringer_vibrate
                        else           -> R.drawable.ic_volume_ringer
                    },
                    enabled = !isRingSilenced && !isRingVibrate,
                    onDrag = onRingDrag,
                    onLongPress = onCycleRinger,
                    onInteractionStart = onInteractionStart,
                    onInteractionEnd = onInteractionEnd,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            // Media (always visible, on the far right)
            IosVolumeSlider(
                colors = colors,
                fraction = mediaVol,
                fillColor = colors.normalFill,
                iconRes = when {
                    mediaVol <= 0f -> R.drawable.ic_volume_off
                    else           -> R.drawable.ic_volume_ringer
                },
                onDrag = onMediaDrag,
                onLongPress = onCycleRinger,
                onInteractionStart = onInteractionStart,
                onInteractionEnd = onInteractionEnd,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // ── Ringer pills (bottom, full width) ───────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200)),
            exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy)) + fadeOut(tween(150)),
        ) {
            IosRingerModeRow(
                colors = colors,
                ringerMode = ringerMode,
                hasVibrator = hasVibrator,
                onSetRinger = onSetRinger,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single vertical volume slider
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IosVolumeSlider(
    colors: IosThemeColors,
    fraction: Float,
    fillColor: Color,
    iconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDrag: (Float) -> Unit,
    onLongPress: () -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    var dragFraction by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }

    // External volume changes sync to dragFraction when not currently dragging
    LaunchedEffect(fraction) {
        if (!isDragging) {
            dragFraction = fraction.coerceIn(0f, 1f)
        }
    }

    val target = dragFraction
    val animFraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f).coerceAtLeast(0.0001f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sliderFraction"
    )
    
    // Elastic overscroll and active squish scaling
    val overscrollAmount = when {
        target > 1f -> target - 1f
        target < 0f -> 0f - target
        else -> 0f
    }
    val targetScaleY = 1f + (overscrollAmount * 0.4f)
    val targetScaleX = if (isDragging) 1.05f else 1f

    val dynamicTint by animateColorAsState(
        targetValue = colors.getDynamicIconTint(animFraction, fillColor == colors.normalFill),
        animationSpec = tween(durationMillis = 150),
        label = "dynamicIconTint"
    )

    val scaleY by animateFloatAsState(
        targetValue = targetScaleY,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sliderScaleY"
    )
    val scaleX by animateFloatAsState(
        targetValue = targetScaleX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sliderScaleX"
    )
    val animFill by animateColorAsState(
        targetValue = if (enabled) fillColor else fillColor.copy(alpha = 0.35f),
        label = "fillColor"
    )

    val view = LocalView.current // Get LocalView.current here

    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 46.dp)
            .scale(scaleX, scaleY)
            .shadow(elevation = 12.dp, shape = IosPillShape)
            .clip(IosPillShape)
            .background(colors.trackBg)
            // Intercept scroll so dragging doesn't scroll QS using drag start/end callbacks directly
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        onInteractionStart()
                    },
                    onDragEnd   = {
                        isDragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        onInteractionEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        onInteractionEnd()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val rawF = 1f - (change.position.y / size.height)
                        // Allow dragging past limits to trigger elastic overscroll
                        dragFraction = rawF
                        val coercedF = rawF.coerceIn(0f, 1f)
                        onDrag(coercedF)
                    }
                )
            }
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = { offset ->
                        if (!enabled) return@detectTapGestures
                        val f = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onDrag(f)
                    },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        // Fill bar (bottom → top)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(animFraction)
                .align(Alignment.BottomCenter)
                .background(animFill, IosPillShape)
        )

        // Icon at bottom of pill
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) dynamicTint else dynamicTint.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(20.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ringer mode row  (mute / vibrate / normal pills)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IosRingerModeRow(
    colors: IosThemeColors,
    ringerMode: Int,
    hasVibrator: Boolean,
    onSetRinger: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(IosRingerShape)
            .background(colors.trackBg)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Silent
        RingerPill(
            colors = colors,
            iconRes = R.drawable.ic_volume_off,
            selected = ringerMode == AudioManager.RINGER_MODE_SILENT,
            selectedColor = colors.silentFill,
            onClick = { onSetRinger(AudioManager.RINGER_MODE_SILENT) },
            modifier = Modifier.weight(1f),
        )
        // Vibrate
        if (hasVibrator) {
            RingerPill(
                colors = colors,
                iconRes = R.drawable.ic_volume_ringer_vibrate,
                selected = ringerMode == AudioManager.RINGER_MODE_VIBRATE,
                selectedColor = colors.vibrateFill,
                onClick = { onSetRinger(AudioManager.RINGER_MODE_VIBRATE) },
                modifier = Modifier.weight(1f),
            )
        }
        // Normal
        RingerPill(
            colors = colors,
            iconRes = R.drawable.ic_volume_ringer,
            selected = ringerMode == AudioManager.RINGER_MODE_NORMAL,
            selectedColor = colors.normalFill,
            onClick = { onSetRinger(AudioManager.RINGER_MODE_NORMAL) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RingerPill(
    colors: IosThemeColors,
    iconRes: Int,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) selectedColor else Color.Transparent,
        animationSpec = tween(200),
        label = "ringerPillBg",
    )
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) {
                // Determine contrasting icon color against the selected pill color
                if (selectedColor == colors.normalFill && colors.isDark) Color(0xFF1C1C1E) else Color.White 
            } else colors.labelColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

// Removed StreamLabel helper to strictly match iOS styling

