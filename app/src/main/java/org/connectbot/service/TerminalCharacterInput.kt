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

package org.connectbot.service

import java.nio.charset.Charset

private const val ESC = 0x1b
private const val DEL = 0x7f
private const val CONTROL_CODE_MASK = 0x1f

/**
 * Classic control code for Ctrl+[codePoint], following xterm conventions, or null
 * when the character has no control form (it is then sent unchanged).
 */
internal fun controlCodeFor(codePoint: Int): Int? = when (codePoint) {
    in 'a'.code..'z'.code, in '@'.code..'_'.code -> codePoint and CONTROL_CODE_MASK
    ' '.code, '2'.code -> 0
    '3'.code -> ESC
    '4'.code -> 0x1c
    '5'.code -> 0x1d
    '6'.code -> 0x1e
    '7'.code, '/'.code -> CONTROL_CODE_MASK
    '8'.code, '?'.code -> DEL
    else -> null
}

/**
 * Encodes a character typed on an on-screen keyboard. Ctrl produces classic control
 * bytes rather than CSI u sequences, so Ctrl+[ is Escape and Ctrl+I is Tab as most
 * programs expect; Alt prefixes the result with Escape.
 */
internal fun encodeTerminalCharacter(codePoint: Int, ctrl: Boolean, alt: Boolean, charset: Charset): ByteArray {
    val control = if (ctrl) controlCodeFor(codePoint) else null
    val body = if (control != null) {
        byteArrayOf(control.toByte())
    } else {
        String(Character.toChars(codePoint)).toByteArray(charset)
    }
    return if (alt) byteArrayOf(ESC.toByte()) + body else body
}
