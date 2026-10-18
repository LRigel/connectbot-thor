/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.components

import android.app.Presentation
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.service.ModifierLevel
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalKeyListener
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.VTermKey

private const val REPEAT_START_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L
private const val RESTORE_DELAY_MS = 200L

private val KeyboardBackground = Color(0xFF000000)
private val KeyColor = Color(0xFF2B2F33)
private val SpecialKeyColor = Color(0xFF1C1F22)
private val PressedKeyColor = Color(0xFF5A6570)
private val TransientModifierColor = Color(0xFF2F6F9F)
private val LockedModifierColor = Color(0xFF4FA3E0)
private val KeyTextColor = Color(0xFFE6E6E6)
private val ShiftHintColor = Color(0xFF8A939B)

/**
 * Shows a full terminal keyboard on a second display (such as the AYN Thor's bottom
 * screen) while the console is visible, typing straight into the current session.
 * It bypasses the system IME, which Android keeps on the display of the focused app.
 *
 * @return whether the keyboard is currently showing, so the caller can hide the
 *   on-screen keyboard and special-keys row on the main display.
 */
@Composable
fun rememberDualScreenKeyboard(bridge: TerminalBridge?, enabled: Boolean): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    val view = LocalView.current
    val shown = remember { mutableStateOf(false) }
    val bridgeState = remember { mutableStateOf<TerminalBridge?>(null) }
    SideEffect { bridgeState.value = bridge }

    LifecycleStartEffect(activity, enabled) {
        val host = if (activity != null && enabled) {
            DualScreenKeyboardHost(activity, view.display?.displayId ?: Display.DEFAULT_DISPLAY, bridgeState, shown)
        } else {
            null
        }
        host?.start()
        onStopOrDispose { host?.stop() }
    }
    return shown.value
}

/**
 * Owns the [Presentation] on the second display and recreates it when Android dismisses
 * it, which happens whenever that display's configuration changes.
 */
private class DualScreenKeyboardHost(
    private val activity: ComponentActivity,
    private val ownerDisplayId: Int,
    private val bridgeState: MutableState<TerminalBridge?>,
    private val shown: MutableState<Boolean>,
) {
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var presentation: Presentation? = null
    private var attachedDisplayId = Display.INVALID_DISPLAY
    private var stopped = false

    private val restore = Runnable { if (!stopped) show() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (presentation == null) show()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == attachedDisplayId) dismiss()
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        displayManager?.registerDisplayListener(displayListener, handler)
        show()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(restore)
        displayManager?.unregisterDisplayListener(displayListener)
        dismiss()
    }

    private fun show() {
        if (stopped || presentation != null || activity.isFinishing) return
        for (display in candidateDisplays()) {
            val next = KeyboardPresentation(activity, display, bridgeState)
            next.setOnDismissListener {
                if (presentation === next) {
                    presentation = null
                    attachedDisplayId = Display.INVALID_DISPLAY
                    shown.value = false
                    handler.removeCallbacks(restore)
                    handler.postDelayed(restore, RESTORE_DELAY_MS)
                }
            }
            try {
                next.show()
            } catch (e: WindowManager.InvalidDisplayException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
            presentation = next
            attachedDisplayId = display.displayId
            shown.value = true
            return
        }
    }

    private fun dismiss() {
        val old = presentation ?: return
        presentation = null
        attachedDisplayId = Display.INVALID_DISPLAY
        shown.value = false
        old.dismiss()
    }

    /**
     * Displays other than the console's own, preferring built-in panels and then the
     * smallest one, so a connected TV is only used when nothing else is available.
     * Presentation-category filtering is skipped because some handhelds leave their
     * second internal panel out of it.
     */
    private fun candidateDisplays(): List<Display> = displayManager?.displays.orEmpty()
        .filter { it.displayId != ownerDisplayId && it.isValid && it.state != Display.STATE_OFF }
        .sortedWith(compareBy<Display> { if (it.isBuiltIn()) 0 else 1 }.thenBy { it.area() })

    private fun Display.isBuiltIn(): Boolean {
        val lower = name.lowercase()
        return displayId == Display.DEFAULT_DISPLAY || "built-in" in lower || "internal" in lower
    }

    private fun Display.area(): Long {
        val size = Point()
        @Suppress("DEPRECATION")
        getRealSize(size)
        return size.x.toLong() * size.y
    }
}

private class KeyboardPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val bridgeState: MutableState<TerminalBridge?>,
) : Presentation(activity, display) {

    init {
        // Never take key focus: hardware keys and the terminal's focus stay on the
        // main display, while touches on this display still reach the keyboard.
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val bridge by bridgeState
                bridge?.let { FullTerminalKeyboard(it) }
                    ?: Box(Modifier.fillMaxSize().background(KeyboardBackground))
            }
        }
        setContentView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    // Some OEMs route handheld buttons to the most recently touched display.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = activity.dispatchKeyEvent(event)
}

/** What a key does when pressed. */
private sealed interface KeyAction {
    data class Character(val normal: Char, val shifted: Char = normal.uppercaseChar(), val fn: Int? = null) : KeyAction
    data class Special(val key: Int) : KeyAction
    data object Backspace : KeyAction
    data object Ctrl : KeyAction
    data object Alt : KeyAction
    data object Shift : KeyAction
    data object Fn : KeyAction
}

private data class KeySpec(val label: String, val action: KeyAction, val weight: Float = 1f)

private fun chars(normal: String, shifted: String, fnKeys: List<Int?> = emptyList()): List<KeySpec> = normal.indices.map { i ->
    KeySpec(normal[i].toString(), KeyAction.Character(normal[i], shifted[i], fnKeys.getOrNull(i)))
}

private val functionKeys = listOf(
    VTermKey.FUNCTION_1, VTermKey.FUNCTION_2, VTermKey.FUNCTION_3, VTermKey.FUNCTION_4, VTermKey.FUNCTION_5,
    VTermKey.FUNCTION_6, VTermKey.FUNCTION_7, VTermKey.FUNCTION_8, VTermKey.FUNCTION_9, VTermKey.FUNCTION_10,
)

private val keyboardRows: List<List<KeySpec>> = listOf(
    listOf(
        KeySpec("Esc", KeyAction.Special(VTermKey.ESCAPE)),
        KeySpec("Tab", KeyAction.Special(VTermKey.TAB)),
        KeySpec("Ctrl", KeyAction.Ctrl),
        KeySpec("Alt", KeyAction.Alt),
        KeySpec("Fn", KeyAction.Fn),
        KeySpec("Home", KeyAction.Special(VTermKey.HOME)),
        KeySpec("End", KeyAction.Special(VTermKey.END)),
        KeySpec("PgUp", KeyAction.Special(VTermKey.PAGEUP)),
        KeySpec("PgDn", KeyAction.Special(VTermKey.PAGEDOWN)),
        KeySpec("Del", KeyAction.Special(VTermKey.DEL)),
    ),
    chars("1234567890", "!@#$%^&*()", functionKeys),
    chars("qwertyuiop", "QWERTYUIOP"),
    chars("asdfghjkl;", "ASDFGHJKL:"),
    listOf(KeySpec("Shift", KeyAction.Shift, 1.5f)) +
        chars("zxcvbnm", "ZXCVBNM") +
        KeySpec("⌫", KeyAction.Backspace, 1.5f),
    chars("-=[]\\'`/,.", "_+{}|\"~?<>", listOf(VTermKey.FUNCTION_11, VTermKey.FUNCTION_12)),
    listOf(
        KeySpec("Space", KeyAction.Character(' ', ' '), 4f),
        KeySpec("←", KeyAction.Special(VTermKey.LEFT)),
        KeySpec("↑", KeyAction.Special(VTermKey.UP)),
        KeySpec("↓", KeyAction.Special(VTermKey.DOWN)),
        KeySpec("→", KeyAction.Special(VTermKey.RIGHT)),
        KeySpec("Enter", KeyAction.Special(VTermKey.ENTER), 2f),
    ),
)

/**
 * A full-screen keyboard with every key a shell needs one tap away: Esc, Tab, Ctrl
 * and Alt, the full ASCII symbol set, arrows, and F1-F12 through Fn.
 */
@Composable
private fun FullTerminalKeyboard(bridge: TerminalBridge) {
    val keyHandler = bridge.keyHandler
    val modifierState by keyHandler.modifierState.collectAsState()
    val delKeyMode by bridge.delKeyModeFlow.collectAsState()
    var fnActive by remember { mutableStateOf(false) }
    val shiftActive = modifierState.shiftState != ModifierLevel.OFF

    fun press(action: KeyAction) {
        when (action) {
            is KeyAction.Character -> {
                val fnKey = action.fn
                if (fnActive && fnKey != null) {
                    keyHandler.sendPressedKey(fnKey)
                } else {
                    bridge.typeCharacter((if (shiftActive) action.shifted else action.normal).code)
                }
                fnActive = false
            }

            is KeyAction.Special -> {
                keyHandler.sendPressedKey(action.key)
                fnActive = false
            }

            KeyAction.Backspace -> {
                bridge.typeCharacter(if (delKeyMode == DelKeyMode.Backspace) 0x08 else 0x7f)
            }

            KeyAction.Ctrl -> keyHandler.metaPress(TerminalKeyListener.CTRL_ON, true)

            KeyAction.Alt -> keyHandler.metaPress(TerminalKeyListener.ALT_ON, true)

            KeyAction.Shift -> keyHandler.metaPress(TerminalKeyListener.SHIFT_ON, true)

            KeyAction.Fn -> fnActive = !fnActive
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KeyboardBackground)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in keyboardRows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (spec in row) {
                    val level = when (spec.action) {
                        KeyAction.Ctrl -> modifierState.ctrlState
                        KeyAction.Alt -> modifierState.altState
                        KeyAction.Shift -> modifierState.shiftState
                        KeyAction.Fn -> if (fnActive) ModifierLevel.TRANSIENT else ModifierLevel.OFF
                        else -> null
                    }
                    KeyboardKey(
                        spec = spec,
                        modifierLevel = level,
                        shiftActive = shiftActive,
                        fnActive = fnActive,
                        onPress = { press(spec.action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyboardKey(
    spec: KeySpec,
    modifierLevel: ModifierLevel?,
    shiftActive: Boolean,
    fnActive: Boolean,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val currentOnPress by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    val action = spec.action
    val repeats = action is KeyAction.Special || action == KeyAction.Backspace ||
        (action is KeyAction.Character && action.normal == ' ')

    val label = when {
        action is KeyAction.Character && fnActive && action.fn != null -> "F${functionNumber(action.fn)}"
        action is KeyAction.Character && shiftActive -> action.shifted.toString()
        else -> spec.label
    }
    val hint = (action as? KeyAction.Character)
        ?.takeIf { !shiftActive && !fnActive && it.shifted != it.normal.uppercaseChar() }
        ?.shifted
        ?.toString()

    val background = when {
        pressed -> PressedKeyColor
        modifierLevel == ModifierLevel.LOCKED -> LockedModifierColor
        modifierLevel == ModifierLevel.TRANSIENT -> TransientModifierColor
        action is KeyAction.Character -> KeyColor
        else -> SpecialKeyColor
    }

    Box(
        modifier = Modifier
            .weight(spec.weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .pointerInput(repeats) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnPress()
                        val repeater = if (repeats) {
                            launch {
                                delay(REPEAT_START_DELAY_MS)
                                while (true) {
                                    currentOnPress()
                                    delay(REPEAT_INTERVAL_MS)
                                }
                            }
                        } else {
                            null
                        }
                        waitForUpOrCancellation()
                        repeater?.cancel()
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = KeyTextColor,
            fontFamily = FontFamily.Monospace,
            fontSize = if (label.length > 1) 13.sp else 20.sp,
            maxLines = 1,
        )
        if (hint != null) {
            Text(
                text = hint,
                color = ShiftHintColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp),
            )
        }
    }
}

private fun functionNumber(key: Int): Int = when (key) {
    VTermKey.FUNCTION_11 -> 11
    VTermKey.FUNCTION_12 -> 12
    else -> functionKeys.indexOf(key) + 1
}
