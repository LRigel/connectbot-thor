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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalCharacterInputTest {

    private fun encode(c: Char, ctrl: Boolean = false, alt: Boolean = false) = encodeTerminalCharacter(c.code, ctrl, alt, Charsets.UTF_8)

    @Test
    fun plainCharacter_isSentAsUtf8() {
        assertArrayEquals("a".toByteArray(), encode('a'))
        assertArrayEquals("é".toByteArray(Charsets.UTF_8), encode('é'))
    }

    @Test
    fun ctrlLetter_isClassicControlCode() {
        assertArrayEquals(byteArrayOf(0x03), encode('c', ctrl = true))
        assertArrayEquals(byteArrayOf(0x03), encode('C', ctrl = true))
    }

    @Test
    fun ctrlBracket_isEscape() {
        assertArrayEquals(byteArrayOf(0x1b), encode('[', ctrl = true))
    }

    @Test
    fun ctrlSpace_isNul() {
        assertArrayEquals(byteArrayOf(0x00), encode(' ', ctrl = true))
    }

    @Test
    fun ctrlWithoutControlForm_sendsCharacter() {
        assertNull(controlCodeFor('1'.code))
        assertArrayEquals("1".toByteArray(), encode('1', ctrl = true))
    }

    @Test
    fun alt_prefixesEscape() {
        assertArrayEquals(byteArrayOf(0x1b, 'x'.code.toByte()), encode('x', alt = true))
        assertArrayEquals(byteArrayOf(0x1b, 0x18), encode('x', ctrl = true, alt = true))
    }

    @Test
    fun ctrlQuestionMark_isDelete() {
        assertEquals(0x7f, controlCodeFor('?'.code))
    }
}
