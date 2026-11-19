package com.koro.kororythm.ui

import com.koro.kororythm.ui.bulk.KeySequence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySequenceTest {
    private val up = 24
    private val down = 25

    @Test fun upDownUpWithinTwoSeconds() {
        val k = KeySequence(listOf(up, down, up))
        assertFalse(k.press(up, 0))
        assertFalse(k.press(down, 400))
        assertTrue(k.press(up, 900))
    }

    @Test fun tooSlowOrWrongOrderDoesNothing() {
        val k = KeySequence(listOf(up, down, up))
        k.press(up, 0); k.press(down, 1500)
        assertFalse(k.press(up, 2100)) // 2.1 s
        val k2 = KeySequence(listOf(up, down, up))
        k2.press(down, 0); k2.press(up, 100)
        assertFalse(k2.press(down, 200))
    }

    @Test fun extraPressesBeforeStillCount() {
        val k = KeySequence(listOf(up, down, up))
        k.press(up, 0); k.press(up, 3000)
        k.press(down, 3300)
        assertTrue(k.press(up, 3600))
        assertFalse(k.press(up, 3700)) // resets after a hit
    }
}
