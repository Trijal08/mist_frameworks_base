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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AppVolume
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.Vibrator
import android.provider.Settings
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientColorMode
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberGradientCustomColors
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberVolumeGradientEnabled
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IosPillShape       = RoundedCornerShape(percent = 50)
private val IosCardShape       = RoundedCornerShape(percent = 50)
private val IosRingerShape     = RoundedCornerShape(percent = 50)

private class IosThemeColors(val isDark: Boolean) {
    val trackBg           = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val glassBg           = if (isDark) Color(0xBF2C2C2E) else Color(0xCCF2F2F7)
    val glassShimmer      = if (isDark) Color(0x14FFFFFF) else Color(0x0AFFFFFF)
    val normalFill        = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1C1C1E)
    val pillSelectedBg    = if (isDark) Color(0xFF48484A) else Color(0xFFD1D1D6)
    val divider           = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val iconTint          = if (isDark) Color.White else Color(0xFF1C1C1E)
    val labelColor        = if (isDark) Color.White.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.60f)
    val silentIconTint    = Color(0xFFFF453A)
    val vibrateIconTint   = Color(0xFF32D74B)

    fun getDynamicIconTint(fraction: Float, isNormalColor: Boolean): Color {
        val isCovered = fraction > 0.15f
        return when {
            isCovered -> if (isNormalColor && isDark) Color(0xFF1C1C1E) else Color.White
            else      -> Color(0xFF8E8E93)
        }
    }
}

@Composable
fun IosVolumePanelScreen(
    dismissTrigger: Boolean,
    expandTrigger: Boolean = false,
    isLeftSide: Boolean = false,
    onExpansionChanged: (Boolean) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
    onDismissed: () -> Unit,
    onExpandConsumed: () -> Unit = {},
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

    var hapticEnabled by remember {
        mutableStateOf(
            Settings.Secure.getIntForUser(
                context.contentResolver,
                Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        )
    }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                hapticEnabled = Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK,
                    1,
                    UserHandle.USER_CURRENT,
                ) != 0
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.VOLUME_DIALOG_HAPTIC_FEEDBACK),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

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

    var showAppVolume by remember {
        mutableStateOf(
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.SHOW_APP_VOLUME,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        )
    }
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val appVolObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                showAppVolume = Settings.System.getIntForUser(
                    context.contentResolver,
                    Settings.System.SHOW_APP_VOLUME,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SHOW_APP_VOLUME),
            false,
            appVolObserver,
            UserHandle.USER_ALL,
        )
        onDispose { context.contentResolver.unregisterContentObserver(appVolObserver) }
    }

    var activeAppVolumes by remember { mutableStateOf<List<AppVolume>>(emptyList()) }

    fun refreshAppVolumes() {
        if (showAppVolume) {
            activeAppVolumes = try {
                audioManager.listAppVolumes().filter { it.isActive }
            } catch (_: Exception) { emptyList() }
        } else {
            activeAppVolumes = emptyList()
        }
    }

    var expanded  by remember { mutableStateOf(false) }
    var visible   by remember { mutableStateOf(true) }

    LaunchedEffect(dismissTrigger) {
        if (dismissTrigger) {
            visible = false
            delay(350)
            onDismissed()
        }
    }

    LaunchedEffect(expanded) { onExpansionChanged(expanded) }

    LaunchedEffect(expandTrigger) {
        if (expandTrigger && !expanded) {
            expanded = true
            onExpandConsumed()
        } else if (expandTrigger) {
            onExpandConsumed()
        }
    }

    LaunchedEffect(expanded, showAppVolume) {
        if (expanded && showAppVolume) {
            while (true) {
                refreshAppVolumes()
                delay(1000)
            }
        } else {
            activeAppVolumes = emptyList()
        }
    }

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
                        refreshAppVolumes()
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

    fun setVol(stream: Int, fraction: Float) {
        val max = audioManager.getStreamMaxVolume(stream)
        val target = (fraction * max).toInt().coerceIn(0, max)
        scope.launch(Dispatchers.IO) {
            try { audioManager.setStreamVolume(stream, target, 0) } catch (_: Exception) {}
        }
    }

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
        if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun setRingerMode(mode: Int) {
        scope.launch(Dispatchers.IO) {
            try { audioManager.ringerModeInternal = mode } catch (_: Exception) {}
        }
        ringerMode = mode
        if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    val numSliders = if (expanded) {
        val apps = if (showAppVolume) activeAppVolumes.size else 0
        3 + apps
    } else 1

    val panelWidth by animateDpAsState(
        targetValue = (38.dp * numSliders) + (12.dp * (numSliders - 1).coerceAtLeast(0)),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "panelWidth"
    )

    val panelHeight by animateDpAsState(
        targetValue = if (expanded) 300.dp else 220.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "panelHeight"
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            initialOffsetX = { if (isLeftSide) -it else it }
        ) + fadeIn(tween(200)),
        exit = slideOutHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            targetOffsetX = { if (isLeftSide) -it else it }
        ) + fadeOut(tween(200)),
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isLeftSide) Modifier.padding(start = 12.dp)
                    else Modifier.padding(end = 12.dp)
                )
                .height(panelHeight)
                .width(panelWidth),
            contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            val isDark = isSystemInDarkTheme()
            val colors = remember(isDark) { IosThemeColors(isDark) }
            IosVolumeCard(
                colors             = colors,
                expanded           = expanded,
                isLeftSide         = isLeftSide,
                hapticEnabled      = hapticEnabled,
                mediaVol           = mediaVol,
                ringVol            = ringVol,
                alarmVol           = alarmVol,
                callVol            = callVol,
                ringerMode         = ringerMode,
                hasVibrator        = hasVibrator,
                activeAppVolumes   = activeAppVolumes,
                onExpandToggle     = { expanded = !expanded },
                onCycleRinger      = { cycleRingerMode() },
                onSetRinger        = { setRingerMode(it) },
                onMediaDrag        = { f -> mediaVol = f;  setVol(AudioManager.STREAM_MUSIC,      f) },
                onRingDrag         = { f -> ringVol  = f;  setVol(AudioManager.STREAM_RING,       f) },
                onAlarmDrag        = { f -> alarmVol = f;  setVol(AudioManager.STREAM_ALARM,      f) },
                onCallDrag         = { f -> callVol  = f;  setVol(AudioManager.STREAM_VOICE_CALL, f) },
                onAppVolumeDrag    = { pkg, f ->
                    scope.launch(Dispatchers.IO) {
                        try { audioManager.setAppVolume(pkg, f) } catch (_: Exception) {}
                    }
                },
                onInteractionStart = onInteractionStart,
                onInteractionEnd   = onInteractionEnd,
            )
        }
    }
}

@Composable
private fun IosVolumeCard(
    colors: IosThemeColors,
    expanded: Boolean,
    isLeftSide: Boolean = false,
    hapticEnabled: Boolean = true,
    mediaVol: Float,
    ringVol: Float,
    alarmVol: Float,
    callVol: Float,
    ringerMode: Int,
    hasVibrator: Boolean,
    activeAppVolumes: List<AppVolume> = emptyList(),
    onExpandToggle: () -> Unit,
    onCycleRinger: () -> Unit,
    onSetRinger: (Int) -> Unit,
    onMediaDrag: (Float) -> Unit,
    onRingDrag: (Float) -> Unit,
    onAlarmDrag: (Float) -> Unit,
    onCallDrag: (Float) -> Unit,
    onAppVolumeDrag: (String, Float) -> Unit = { _, _ -> },
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val isRingSilenced = ringerMode == AudioManager.RINGER_MODE_SILENT
    val isRingVibrate  = ringerMode == AudioManager.RINGER_MODE_VIBRATE

    val columnAlignment = if (isLeftSide) Alignment.Start else Alignment.End

    val gradientEnabled = rememberVolumeGradientEnabled()
    val gradientColors: List<Color>? = if (gradientEnabled) {
        if (rememberGradientColorMode() == 1) {
            val (start, end) = rememberGradientCustomColors()
            listOf(start.copy(alpha = 1f), end.copy(alpha = 1f))
        } else {
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
            )
        }
    } else null

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = columnAlignment,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (isLeftSide) {
                IosVolumeSlider(
                    colors = colors,
                    fraction = mediaVol,
                    fillColor = colors.normalFill,
                    gradientColors = gradientColors,
                    iconRes = if (mediaVol <= 0f) R.drawable.ic_volume_media_mute else R.drawable.ic_volume_media,
                    hapticEnabled = hapticEnabled,
                    onDrag = onMediaDrag,
                    onLongPress = onCycleRinger,
                    onInteractionStart = onInteractionStart,
                    onInteractionEnd = onInteractionEnd,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                if (expanded) {
                    IosVolumeSlider(
                        colors = colors,
                        fraction = if (isRingSilenced || isRingVibrate) 0f else ringVol,
                        fillColor = if (isRingSilenced || isRingVibrate) Color.Transparent else colors.normalFill,
                        gradientColors = if (isRingSilenced || isRingVibrate) null else gradientColors,
                        iconRes = when {
                            isRingSilenced -> R.drawable.ic_volume_off
                            isRingVibrate  -> R.drawable.ic_volume_ringer_vibrate
                            else           -> R.drawable.ic_volume_ringer
                        },
                        enabled = !isRingSilenced && !isRingVibrate,
                        hapticEnabled = hapticEnabled,
                        onDrag = onRingDrag,
                        onLongPress = onCycleRinger,
                        onInteractionStart = onInteractionStart,
                        onInteractionEnd = onInteractionEnd,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    IosVolumeSlider(
                        colors = colors,
                        fraction = alarmVol,
                        fillColor = colors.normalFill,
                        gradientColors = gradientColors,
                        iconRes = R.drawable.ic_volume_alarm,
                        hapticEnabled = hapticEnabled,
                        onDrag = onAlarmDrag,
                        onLongPress = {},
                        onInteractionStart = onInteractionStart,
                        onInteractionEnd = onInteractionEnd,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    for (appVol in activeAppVolumes) {
                        IosAppVolumeSlider(
                            colors = colors,
                            appVolume = appVol,
                            gradientColors = gradientColors,
                            hapticEnabled = hapticEnabled,
                            onDrag = { f -> onAppVolumeDrag(appVol.packageName, f) },
                            onInteractionStart = onInteractionStart,
                            onInteractionEnd = onInteractionEnd,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else {
                if (expanded) {
                    for (appVol in activeAppVolumes.reversed()) {
                        IosAppVolumeSlider(
                            colors = colors,
                            appVolume = appVol,
                            gradientColors = gradientColors,
                            hapticEnabled = hapticEnabled,
                            onDrag = { f -> onAppVolumeDrag(appVol.packageName, f) },
                            onInteractionStart = onInteractionStart,
                            onInteractionEnd = onInteractionEnd,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    IosVolumeSlider(
                        colors = colors,
                        fraction = alarmVol,
                        fillColor = colors.normalFill,
                        gradientColors = gradientColors,
                        iconRes = R.drawable.ic_volume_alarm,
                        hapticEnabled = hapticEnabled,
                        onDrag = onAlarmDrag,
                        onLongPress = {},
                        onInteractionStart = onInteractionStart,
                        onInteractionEnd = onInteractionEnd,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    IosVolumeSlider(
                        colors = colors,
                        fraction = if (isRingSilenced || isRingVibrate) 0f else ringVol,
                        fillColor = if (isRingSilenced || isRingVibrate) Color.Transparent else colors.normalFill,
                        gradientColors = if (isRingSilenced || isRingVibrate) null else gradientColors,
                        iconRes = when {
                            isRingSilenced -> R.drawable.ic_volume_off
                            isRingVibrate  -> R.drawable.ic_volume_ringer_vibrate
                            else           -> R.drawable.ic_volume_ringer
                        },
                        enabled = !isRingSilenced && !isRingVibrate,
                        hapticEnabled = hapticEnabled,
                        onDrag = onRingDrag,
                        onLongPress = onCycleRinger,
                        onInteractionStart = onInteractionStart,
                        onInteractionEnd = onInteractionEnd,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                IosVolumeSlider(
                    colors = colors,
                    fraction = mediaVol,
                    fillColor = colors.normalFill,
                    gradientColors = gradientColors,
                    iconRes = if (mediaVol <= 0f) R.drawable.ic_volume_media_mute else R.drawable.ic_volume_media,
                    hapticEnabled = hapticEnabled,
                    onDrag = onMediaDrag,
                    onLongPress = onCycleRinger,
                    onInteractionStart = onInteractionStart,
                    onInteractionEnd = onInteractionEnd,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

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

@Composable
private fun IosVolumeSlider(
    colors: IosThemeColors,
    fraction: Float,
    fillColor: Color,
    iconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticEnabled: Boolean = true,
    gradientColors: List<Color>? = null,
    onDrag: (Float) -> Unit,
    onLongPress: () -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    var dragFraction by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }
    var externalPulse by remember { mutableStateOf(false) }

    LaunchedEffect(fraction) {
        if (!isDragging) {
            dragFraction = fraction.coerceIn(0f, 1f)
            // Trigger the elastic 1.05x widening scale for a short duration
            externalPulse = true
            delay(150)
            externalPulse = false
        }
    }

    val target = dragFraction
    val animFraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f).coerceAtLeast(0.0001f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "sliderFraction"
    )
    
    val overscrollAmount = when {
        target > 1f -> target - 1f
        target < 0f -> 0f - target
        else -> 0f
    }
    val targetScaleY = 1f + (overscrollAmount * 0.4f)
    val targetScaleX = if (isDragging || externalPulse) 1.05f else 1f

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

    val view = LocalView.current

    var lastHapticStep by remember { mutableIntStateOf(-1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 46.dp)
            .scale(scaleX, scaleY)
            .shadow(elevation = 12.dp, shape = IosPillShape)
            .clip(IosPillShape)
            .drawBehind {
                drawRect(color = colors.glassBg)
                val fillPx = size.height * animFraction
                val unfilledTop = 0f
                val unfilledBottom = size.height - fillPx
                if (unfilledBottom > unfilledTop) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(colors.glassShimmer, Color.Transparent),
                            startY = unfilledTop,
                            endY = unfilledBottom,
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, unfilledTop),
                        size = androidx.compose.ui.geometry.Size(size.width, unfilledBottom - unfilledTop),
                    )
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        lastHapticStep = -1
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        onInteractionStart()
                    },
                    onDragEnd = {
                        isDragging = false
                        lastHapticStep = -1
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        onInteractionEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        lastHapticStep = -1
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        onInteractionEnd()
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val rawF = 1f - (change.position.y / size.height)
                        dragFraction = rawF
                        val coercedF = rawF.coerceIn(0f, 1f)
                        onDrag(coercedF)
                        if (hapticEnabled) {
                            val step = (coercedF * 20).toInt()
                            if (step != lastHapticStep) {
                                lastHapticStep = step
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(animFraction)
                .align(Alignment.BottomCenter)
                .then(
                    if (gradientColors != null) {
                        val alphaScale = if (enabled) 1f else 0.35f
                        val scaledColors = gradientColors.map { it.copy(alpha = it.alpha * alphaScale) }
                        Modifier
                            .clip(IosPillShape)
                            .drawBehind {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = scaledColors,
                                        startY = size.height,
                                        endY = 0f,
                                    )
                                )
                            }
                    } else {
                        Modifier.background(animFill, IosPillShape)
                    }
                )
        )

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
            .clip(IosCardShape)
            .drawBehind { drawRect(color = colors.glassBg) }
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RingerPill(
            colors = colors,
            iconRes = R.drawable.ic_volume_off,
            selected = ringerMode == AudioManager.RINGER_MODE_SILENT,
            selectedColor = colors.pillSelectedBg,
            selectedIconTint = colors.silentIconTint,
            onClick = { onSetRinger(AudioManager.RINGER_MODE_SILENT) },
            modifier = Modifier.weight(1f),
        )
        if (hasVibrator) {
            RingerPill(
                colors = colors,
                iconRes = R.drawable.ic_volume_ringer_vibrate,
                selected = ringerMode == AudioManager.RINGER_MODE_VIBRATE,
                selectedColor = colors.pillSelectedBg,
                selectedIconTint = colors.vibrateIconTint,
                onClick = { onSetRinger(AudioManager.RINGER_MODE_VIBRATE) },
                modifier = Modifier.weight(1f),
            )
        }
        RingerPill(
            colors = colors,
            iconRes = R.drawable.ic_volume_ringer,
            selected = ringerMode == AudioManager.RINGER_MODE_NORMAL,
            selectedColor = colors.pillSelectedBg,
            selectedIconTint = colors.iconTint,
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
    selectedIconTint: Color = colors.iconTint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) selectedColor else Color.Transparent,
        animationSpec = tween(200),
        label = "ringerPillBg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) selectedIconTint else colors.labelColor,
        animationSpec = tween(200),
        label = "ringerPillIconTint",
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
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun IosAppVolumeSlider(
    colors: IosThemeColors,
    appVolume: AppVolume,
    modifier: Modifier = Modifier,
    gradientColors: List<Color>? = null,
    hapticEnabled: Boolean = true,
    onDrag: (Float) -> Unit,
    onInteractionStart: () -> Unit,
    onInteractionEnd: () -> Unit,
) {
    val iconRes = if (appVolume.isMuted || appVolume.volume <= 0f)
        R.drawable.ic_volume_off
    else
        R.drawable.ic_volume_ringer

    IosVolumeSlider(
        colors          = colors,
        fraction        = if (appVolume.isMuted) 0f else appVolume.volume.coerceIn(0f, 1f),
        fillColor       = colors.normalFill,
        gradientColors  = if (appVolume.isMuted) null else gradientColors,
        iconRes         = iconRes,
        enabled         = !appVolume.isMuted,
        hapticEnabled   = hapticEnabled,
        onDrag          = onDrag,
        onLongPress     = {},
        onInteractionStart = onInteractionStart,
        onInteractionEnd   = onInteractionEnd,
        modifier        = modifier,
    )
}
