/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.qs.panels.ui.viewmodel.TileGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel

private val IOS_PANEL_HIDDEN_TILE_SPECS = setOf("internet", "bt")

/**
 * Displays a grid of tiles with an optional reveal animation.
 *
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 */
@Composable
fun ContentScope.TileGrid(
    viewModel: TileGridViewModel,
    modifier: Modifier = Modifier,
    listening: () -> Boolean = { true },
    enableRevealEffect: Boolean = false,
) {
    val context = LocalContext.current
    val cr = context.contentResolver

    fun readIosPanelEnabled(): Boolean = try {
        Settings.System.getIntForUser(
            cr, "qs_ios_control_panel", 0, UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    var iosPanelEnabled by remember { mutableStateOf(readIosPanelEnabled()) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                iosPanelEnabled = readIosPanelEnabled()
            }
        }
        try {
            cr.registerContentObserver(
                Settings.System.getUriFor("qs_ios_control_panel"),
                false, observer, UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}
        onDispose { cr.unregisterContentObserver(observer) }
    }

    val gridLayout = viewModel.gridLayout
    val allTiles: List<TileViewModel> = viewModel.tileViewModels

    val tiles: List<TileViewModel> = if (iosPanelEnabled) {
        allTiles.filter { it.spec.spec !in IOS_PANEL_HIDDEN_TILE_SPECS }
    } else {
        allTiles
    }

    with(gridLayout) {
        TileGrid(
            tiles = tiles,
            modifier = modifier,
            listening = listening,
            enableRevealEffect = enableRevealEffect,
        )
    }
}
