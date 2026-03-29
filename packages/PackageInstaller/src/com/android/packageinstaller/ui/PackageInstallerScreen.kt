/*
 * Copyright (C) 2026 MistOS
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller.ui
 
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.*
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
 
enum class InstallerPhase {
    CONFIRM, INSTALLING, INSTALL_SUCCESS, INSTALL_FAILED,
    UNINSTALL_CONFIRM, UNINSTALLING, UNINSTALL_SUCCESS, UNINSTALL_FAILED,
    UPDATE_CONFIRM, REINSTALL_CONFIRM, DOWNGRADE_CONFIRM
}
 
data class AppInfoData(
    val name: String,
    val iconDrawable: android.graphics.drawable.Drawable?,
    val packageName: String,
    val version: String,
    val currentVersion: String?,
    val sizeMb: Float,
    val targetSdk: Int,
    val currentTargetSdk: Int? = null,
    val minSdk: Int,
    val isSystemApp: Boolean = false
)
 
private fun apiToAndroidVersion(api: Int): String {
    return when (api) {
        35 -> "15"
        34 -> "14"
        33 -> "13"
        32 -> "12L"
        31 -> "12"
        30 -> "11"
        29 -> "10"
        28 -> "9"
        27 -> "8.1"
        26 -> "8.0"
        else -> if (api > 35) "Next" else "Old"
    }
}
 
data class HyperThemeContext(
    val BgDeep: Color,
    val BgCard: Color,
    val BgGlass: Color,
    val BgGlassBorder: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val Accent: Color,
    val AccentContainer: Color,
    val AccentAmber: Color = Color(0xFFF59E0B),
    val AccentRed: Color = Color(0xFFEF4444)
)
 
@Composable
fun currentHyperTheme(): HyperThemeContext {
    val isDark = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme
    
    return HyperThemeContext(
        BgDeep = Color.Transparent,
        BgCard = if (isDark) colorScheme.surface.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f),
        BgGlass = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f),
        BgGlassBorder = if (isDark) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.45f),
        TextPrimary = colorScheme.onSurface,
        TextSecondary = colorScheme.onSurfaceVariant.copy(alpha = 0.95f),
        TextTertiary = colorScheme.outline,
        Accent = colorScheme.primary,
        AccentContainer = colorScheme.primaryContainer
    )
}
 
@Composable
fun PackageInstallerScreen(
    appInfo: AppInfoData,
    initialPhase: InstallerPhase = InstallerPhase.CONFIRM,
    onInstallConfirmed: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var phase by remember { mutableStateOf(initialPhase) }
    val progress by remember { mutableFloatStateOf(0f) }
    val theme = currentHyperTheme()
 
    Box(
        modifier = Modifier.fillMaxSize().background(theme.BgDeep),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedMeshBackground(theme.Accent)
 
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp) 
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    (fadeIn(tween(600)) + scaleIn(initialScale = 0.94f) + slideInVertically { it / 6 }) togetherWith
                    (fadeOut(tween(400)) + scaleOut(targetScale = 0.97f) + slideOutVertically { -it / 12 })
                },
                label = "phase"
            ) { p ->
                when (p) {
                    InstallerPhase.CONFIRM, InstallerPhase.UPDATE_CONFIRM, 
                    InstallerPhase.REINSTALL_CONFIRM, InstallerPhase.DOWNGRADE_CONFIRM -> 
                        ConfirmSheet(theme, appInfo, p, onInstall = onInstallConfirmed, onDismiss = onDismiss)
                    
                    InstallerPhase.INSTALLING -> ProgressSheet(theme, appInfo, progress, isUninstall = false)
                    InstallerPhase.INSTALL_SUCCESS -> InstallSuccessSheet(theme, appInfo, onOpenApp, onDismiss)
                    InstallerPhase.INSTALL_FAILED -> InstallFailedSheet(theme, appInfo, { phase = InstallerPhase.CONFIRM }, onDismiss)
                    
                    InstallerPhase.UNINSTALL_CONFIRM -> UninstallConfirmSheet(theme, appInfo, onInstallConfirmed, onDismiss)
                    InstallerPhase.UNINSTALLING -> ProgressSheet(theme, appInfo, progress, isUninstall = true)
                    InstallerPhase.UNINSTALL_SUCCESS -> UninstallSuccessSheet(theme, appInfo, onDismiss)
                    InstallerPhase.UNINSTALL_FAILED -> UninstallFailedSheet(theme, appInfo, { phase = InstallerPhase.UNINSTALL_CONFIRM }, onDismiss)
                }
            }
        }
    }
}
 
@Composable
fun AnimatedMeshBackground(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val phase1 by infiniteTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), "p1")
    val phase2 by infiniteTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart), "p2")
    val isDark = isSystemInDarkTheme()
    val alphaBoost = if (isDark) 0.2f else 0.5f
    
    Canvas(Modifier.fillMaxSize().blur(140.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(
            Brush.radialGradient(listOf(accentColor.copy(alpha = alphaBoost), Color.Transparent),
                center = Offset(w * (0.4f + 0.35f * cos(2 * PI * phase1).toFloat()), h * (0.35f + 0.25f * sin(2 * PI * phase1).toFloat())),
                radius = w * 0.9f)
        )
        drawCircle(
            Brush.radialGradient(listOf(accentColor.copy(alpha = alphaBoost * 0.7f), Color.Transparent),
                center = Offset(w * (0.3f + 0.45f * sin(2 * PI * phase2).toFloat()), h * (0.75f + 0.35f * cos(2 * PI * phase2).toFloat())),
                radius = w * 1.0f)
        )
    }
}
 
@Composable
fun GlassCard(theme: HyperThemeContext, content: @Composable ColumnScope.() -> Unit) {
    val isDark = isSystemInDarkTheme()
    Box(
        Modifier.fillMaxWidth()
            .then(if (isDark) Modifier else Modifier.padding(2.dp))
            .clip(RoundedCornerShape(44.dp))
            .background(theme.BgCard)
            .border(
                1.5.dp,
                Brush.linearGradient(
                    listOf(
                        theme.Accent.copy(alpha = if (isDark) 0.45f else 0.7f),
                        theme.BgGlassBorder.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(800f, 800f)
                ),
                RoundedCornerShape(44.dp)
            )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp), content = content)
    }
}
 
@Composable
fun InnerTuneHeader(app: AppInfoData, theme: HyperThemeContext, scale: Float = 1f) {
    Column(
        modifier = Modifier.fillMaxWidth().scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppIconBadgeView(app.iconDrawable, size = 88.dp, glowing = false, accent = theme.Accent)
        Spacer(Modifier.height(24.dp))
        Text(
            app.name,
            color = theme.TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            letterSpacing = (-1.0).sp
        )
        Text(
            app.packageName,
            color = theme.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Ellipsis
        )
        if (app.isSystemApp) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(theme.AccentAmber))
                Text("System Component", color = theme.AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
 
@Composable
fun PillBanner(label: String, color: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.2.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
    }
}
 
@Composable
fun TransitionGrid(app: AppInfoData, theme: HyperThemeContext) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Version Transition
        if (app.currentVersion != null && app.currentVersion != app.version) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Version Info", color = theme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetaChip(theme, app.currentVersion, isAccent = false)
                    Text("→", color = theme.Accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    MetaChip(theme, app.version, isAccent = true)
                }
            }
        } else {
            SingleInfoRow("Version Build", app.version, theme)
        }
        
        val targetVer = apiToAndroidVersion(app.targetSdk)
        if (app.currentTargetSdk != null && app.currentTargetSdk != app.targetSdk) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("System Update", color = theme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetaChip(theme, "API ${app.currentTargetSdk}", isAccent = false)
                    Text("→", color = theme.Accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    MetaChip(theme, "API ${app.targetSdk}", isAccent = true)
                }
                Spacer(Modifier.height(6.dp))
                Text("Android $targetVer Target", color = theme.Accent.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        } else {
            SingleInfoRow("Compatibility", "Android $targetVer (API ${app.targetSdk})", theme)
        }
    }
}
 
@Composable
fun SingleInfoRow(label: String, value: String, theme: HyperThemeContext) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = theme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        MetaChip(theme, value, isAccent = true)
    }
}
 
@Composable
fun AppIconBadgeView(icon: android.graphics.drawable.Drawable?, size: Dp = 72.dp, glowing: Boolean = false, accent: Color) {
    val isDark = isSystemInDarkTheme()
    val glowAnim = rememberInfiniteTransition(label = "iconGlow")
    val glowAlpha by glowAnim.animateFloat(
        0.1f, 0.35f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ga"
    )
    val glowScale by glowAnim.animateFloat(
        1.05f, 1.3f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "gs"
    )
    Box(contentAlignment = Alignment.Center) {
        if (glowing) {
            Canvas(Modifier.size(size * 1.8f).scale(glowScale)) {
                drawCircle(Brush.radialGradient(
                    listOf(accent.copy(alpha = glowAlpha), Color.Transparent)
                ))
            }
        }
        Box(
            Modifier.size(size)
                .clip(RoundedCornerShape(size * 0.3f))
                .background(if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f))
                .border(1.5.dp, if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(size * 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { ctx ->
                    android.widget.ImageView(ctx).apply { setImageDrawable(icon); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
                }, modifier = Modifier.fillMaxSize().padding(size * 0.15f))
            } else {
                Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Text("A", color = accent, fontSize = (size.value * 0.4).sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
 
@Composable
fun ConfirmSheet(theme: HyperThemeContext, app: AppInfoData, phase: InstallerPhase, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 280f)) }
    
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(36.dp))
        
        InnerTuneHeader(app, theme, scale.value)
        Spacer(Modifier.height(36.dp))
        
        val bannerLabel = when (phase) {
            InstallerPhase.UPDATE_CONFIRM -> "Update this application?"
            InstallerPhase.REINSTALL_CONFIRM -> "Reinstall this application?"
            InstallerPhase.DOWNGRADE_CONFIRM -> "Downgrade this application?"
            else -> "Install this application?"
        }
        PillBanner(bannerLabel, theme.Accent, theme.TextPrimary)
        Spacer(Modifier.height(32.dp))
        
        TransitionGrid(app, theme)
        Spacer(Modifier.height(44.dp))
        
        val actionLabel = when (phase) {
            InstallerPhase.UPDATE_CONFIRM -> "Update App"
            InstallerPhase.DOWNGRADE_CONFIRM -> "Install Anyway"
            else -> "Install"
        }
        HyperButton(actionLabel, theme.Accent, onClick = onInstall)
        Spacer(Modifier.height(16.dp))
        HyperButton("Cancel", theme.BgGlass, theme.TextPrimary, onClick = onDismiss)
    }
}
 
@Composable
fun ProgressSheet(theme: HyperThemeContext, app: AppInfoData, progress: Float, isUninstall: Boolean) {
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(64.dp))
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            RingProgress(progress, theme.Accent)
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                AppIconBadgeView(app.iconDrawable, 84.dp, glowing = true, accent = theme.Accent)
            }
        }
        Spacer(Modifier.height(48.dp))
        Text(
            if (isUninstall) "Uninstalling…" else "Installing…",
            Modifier.align(Alignment.CenterHorizontally),
            color = theme.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black
        )
        Text(app.name, Modifier.align(Alignment.CenterHorizontally), color = theme.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(64.dp))
    }
}
 
@Composable
fun InstallSuccessSheet(theme: HyperThemeContext, app: AppInfoData, onOpen: () -> Unit, onDone: () -> Unit) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 250f)) }
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(56.dp))
        Box(Modifier.align(Alignment.CenterHorizontally).scale(scale.value)) {
            Canvas(Modifier.size(110.dp)) {
                drawCircle(theme.Accent.copy(alpha = 0.18f))
            }
            Box(Modifier.size(88.dp).align(Alignment.Center).clip(CircleShape).background(theme.Accent.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Text("\u2713", color = theme.Accent, fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Finished Successfully!", Modifier.align(Alignment.CenterHorizontally), color = theme.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(app.name, Modifier.align(Alignment.CenterHorizontally), color = theme.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(48.dp))
        HyperButton("Open Application", theme.Accent, onClick = onOpen)
        Spacer(Modifier.height(16.dp))
        HyperButton("Done", theme.BgGlass, theme.TextPrimary, onClick = onDone)
    }
}
 
@Composable
fun InstallFailedSheet(theme: HyperThemeContext, app: AppInfoData, onRetry: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(56.dp))
        Box(Modifier.align(Alignment.CenterHorizontally).size(88.dp).clip(CircleShape).background(theme.AccentRed.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text("\u2715", color = theme.AccentRed, fontSize = 48.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(32.dp))
        Text("Installation Failed", Modifier.align(Alignment.CenterHorizontally), color = theme.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(48.dp))
        HyperButton("Try Again", theme.Accent, onClick = onRetry)
        Spacer(Modifier.height(16.dp))
        HyperButton("Cancel", theme.BgGlass, theme.TextPrimary, onClick = onDismiss)
    }
}
 
@Composable
fun UninstallConfirmSheet(theme: HyperThemeContext, app: AppInfoData, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scale = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 300f)) }
    
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(36.dp))
        InnerTuneHeader(app, theme, scale.value)
        Spacer(Modifier.height(36.dp))
        
        PillBanner("Uninstall this application?", theme.AccentRed, theme.TextPrimary)
        Spacer(Modifier.height(28.dp))
        Text(
            "This will permanently remove the application and its data from this device.",
            color = theme.TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
            lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 16.dp),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(44.dp))
        
        val actionLabel = if (app.isSystemApp) "Uninstall Updates" else "Uninstall"
        HyperButton(actionLabel, theme.AccentRed, onClick = onConfirm)
        Spacer(Modifier.height(16.dp))
        HyperButton("Keep Application", theme.BgGlass, theme.TextPrimary, onClick = onDismiss)
    }
}
 
@Composable
fun UninstallSuccessSheet(theme: HyperThemeContext, app: AppInfoData, onDone: () -> Unit) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 280f))
        delay(1200)
        onDone()
    }
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(64.dp))
        Box(Modifier.align(Alignment.CenterHorizontally).scale(scale.value)) {
            Canvas(Modifier.size(110.dp)) {
                drawCircle(theme.Accent.copy(alpha = 0.18f))
            }
            Box(Modifier.size(88.dp).align(Alignment.Center).clip(CircleShape).background(theme.Accent.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Text("\u2713", color = theme.Accent, fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(36.dp))
        Text("Uninstalled!", Modifier.align(Alignment.CenterHorizontally), color = theme.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("${app.name} removed.", Modifier.align(Alignment.CenterHorizontally), color = theme.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(64.dp))
    }
}
 
@Composable
fun UninstallFailedSheet(theme: HyperThemeContext, app: AppInfoData, onRetry: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(theme) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp, 5.dp).clip(CircleShape).background(theme.TextTertiary.copy(alpha = 0.35f)))
        Spacer(Modifier.height(56.dp))
        Box(Modifier.align(Alignment.CenterHorizontally).size(88.dp).clip(CircleShape).background(theme.AccentRed.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text("\u2715", color = theme.AccentRed, fontSize = 48.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(36.dp))
        Text("Removal Failed", Modifier.align(Alignment.CenterHorizontally), color = theme.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(48.dp))
        HyperButton("Try Again", theme.AccentRed, onClick = onRetry)
        Spacer(Modifier.height(16.dp))
        HyperButton("Cancel", theme.BgGlass, theme.TextPrimary, onClick = onDismiss)
    }
}
 
@Composable
fun MetaChip(theme: HyperThemeContext, label: String, isAccent: Boolean = false) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (isAccent) theme.Accent.copy(alpha = 0.18f) else theme.BgGlass)
            .border(1.2.dp, if (isAccent) theme.Accent.copy(alpha = 0.35f) else theme.BgGlassBorder, CircleShape)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (isAccent) theme.Accent else theme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
 
@Composable
fun RingProgress(progress: Float, color: Color) {
    val t = rememberInfiniteTransition(label = "ring")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), "angle")
    
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.12f), Color.Transparent)), radius = size.width / 2)
        }
        Canvas(Modifier.size(132.dp).rotate(if (progress <= 0f) angle else 0f)) {
            val stroke = 12.dp.toPx()
            drawCircle(color.copy(alpha = 0.15f), center = center, radius = size.width/2 - stroke/2, style = Stroke(stroke))
            drawArc(
                brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.3f), color, color)),
                startAngle = -90f,
                sweepAngle = if (progress <= 0f) 170f else 360f * progress,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    }
}
 
@Composable
fun HyperButton(label: String, color: Color, labelColor: Color = Color.White, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "bs")
    val isDark = isSystemInDarkTheme()
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable(src, null, onClick = onClick)
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (color.alpha < 1f) {
                if (isDark) Color.White else Color.Black
            } else labelColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
    }
}
