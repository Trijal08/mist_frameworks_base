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

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class IosVolumeDialog(
    context: Context,
    private val onExpansionChanged: (Boolean) -> Unit,
    private val onInteractionStart: () -> Unit,
    private val onInteractionEnd: () -> Unit,
    private val onDismiss: () -> Unit,
) : Dialog(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    var dismissTrigger by mutableStateOf(false)
        private set

    var expandTrigger by mutableStateOf(false)
        private set

    var isLeftSide by mutableStateOf(false)

    init {
        savedStateRegistryController.performRestore(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val win = window!!

        win.setBackgroundDrawableResource(android.R.color.transparent)
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        win.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        win.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
        win.setWindowAnimations(0)

        val lp = win.attributes
        lp.format = PixelFormat.TRANSLUCENT
        lp.gravity = Gravity.CENTER_VERTICAL or if (isLeftSide) Gravity.START else Gravity.END
        lp.title = "IosVolumeDialog"
        lp.windowAnimations = -1
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        win.attributes = lp
        win.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@IosVolumeDialog)
            setViewTreeSavedStateRegistryOwner(this@IosVolumeDialog)
            setContent {
                IosVolumePanelScreen(
                    dismissTrigger = dismissTrigger,
                    expandTrigger = expandTrigger,
                    isLeftSide = isLeftSide,
                    onExpansionChanged = onExpansionChanged,
                    onInteractionStart = onInteractionStart,
                    onInteractionEnd = onInteractionEnd,
                    onDismissed = {
                        dismissTrigger = false
                        dismiss()
                        onDismiss()
                    },
                    onExpandConsumed = { expandTrigger = false },
                )
            }
        }

        setContentView(composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        setCanceledOnTouchOutside(true)
        setOnDismissListener { onDismiss() }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    fun dismissAnimated() {
        dismissTrigger = true
    }

    fun expandPanel() {
        expandTrigger = true
    }

    fun updateWindowGravity(isLeftSide: Boolean) {
        this.isLeftSide = isLeftSide
        window?.setGravity(
            (if (isLeftSide) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        )
    }

    fun quickDismiss() {
        dismissTrigger = false
        if (isShowing) dismiss()
    }
}
