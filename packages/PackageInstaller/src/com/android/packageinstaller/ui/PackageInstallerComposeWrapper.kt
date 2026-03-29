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

import android.app.Activity
import androidx.compose.ui.platform.ComposeView
import kotlin.math.round
import android.util.Log

object PackageInstallerComposeBridge {
    @JvmStatic
    fun setPackageInstallerContent(
        activity: Activity,
        appName: String,
        appIcon: android.graphics.drawable.Drawable?,
        packageName: String,
        version: String,
        currentVersion: String?,
        sizeBytes: Long,
        targetSdk: Int,
        minSdk: Int,
        currentTargetSdk: Int? = null,
        initialPhase: InstallerPhase = InstallerPhase.CONFIRM,
        onInstallConfirmed: () -> Unit = {},
        onOpenApp: () -> Unit = {},
        onCancel: () -> Unit = {},
        isSystemApp: Boolean = false
    ) {
        Log.e("PackageInstallerCompose", "==== CUSTOM UI LOG ==== setPackageInstallerContent called! App: $appName")
        val sizeMb = round((sizeBytes.toFloat() / (1024f * 1024f)) * 10f) / 10f
        
        val appInfo = AppInfoData(
            name = appName,
            iconDrawable = appIcon,
            packageName = packageName,
            version = version,
            currentVersion = currentVersion,
            sizeMb = sizeMb,
            targetSdk = targetSdk,
            currentTargetSdk = currentTargetSdk,
            minSdk = minSdk,
            isSystemApp = isSystemApp
        )

        val composeView = ComposeView(activity).apply {
            setContent {
                PackageInstallerScreen(
                    appInfo = appInfo,
                    initialPhase = initialPhase,
                    onInstallConfirmed = onInstallConfirmed,
                    onOpenApp = onOpenApp,
                    onDismiss = onCancel
                )
            }
        }
        
        activity.setContentView(composeView)
        
        activity.window.apply {
            if (android.os.Build.VERSION.SDK_INT >= 31 && decorView != null) {
                setBackgroundBlurRadius(120)
            }
            setDimAmount(0.05f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
