package com.slowva.player.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBindingsTest {
    private val d = KeyMap.DEFAULT
    private fun k(code: Int, meta: Int = 0) = KeyCombo(code, meta)

    @Test fun defaultsAreBlackBerryStyle() {
        assertEquals(KeyAction.PLAY_PAUSE, d.lookup(k(KeyEvent.KEYCODE_SPACE)))
        assertEquals(KeyAction.PLAY_PAUSE, d.lookup(k(KeyEvent.KEYCODE_K)))
        assertEquals(KeyAction.SEEK_FORWARD, d.lookup(k(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(KeyAction.SEEK_BACK, d.lookup(k(KeyEvent.KEYCODE_J)))
        assertEquals(KeyAction.SEARCH, d.lookup(k(KeyEvent.KEYCODE_SLASH)))
        assertEquals(KeyAction.TAB_4, d.lookup(k(KeyEvent.KEYCODE_4)))
        assertEquals(listOf(k(KeyEvent.KEYCODE_SPACE), k(KeyEvent.KEYCODE_K)).toSet(), d.keysFor(KeyAction.PLAY_PAUSE).toSet())
        assertNull(d.lookup(k(KeyEvent.KEYCODE_Z)))
    }

    @Test fun metaMatchesExactlyForLetters() {
        assertNull(d.lookup(KeyCombo.of(KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)))
        val m = d.bind(KeyAction.LIKE, KeyCombo.of(KeyEvent.KEYCODE_N, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertEquals(KeyAction.LIKE, m.lookup(k(KeyEvent.KEYCODE_N, KeyEvent.META_ALT_ON)))
        assertEquals(KeyAction.NEXT, m.lookup(k(KeyEvent.KEYCODE_N)))
    }

    @Test fun nonLettersFallBackToNoModifiers() {
        // Alt+digit on a BlackBerry keyboard still picks the tab
        assertEquals(KeyAction.TAB_1, d.lookup(KeyCombo.of(KeyEvent.KEYCODE_1, KeyEvent.META_ALT_ON)))
        assertEquals(KeyAction.SEARCH, d.lookup(KeyCombo.of(KeyEvent.KEYCODE_SLASH, KeyEvent.META_SYM_ON)))
    }

    @Test fun ofDropsLockAndSideBits() {
        val c = KeyCombo.of(KeyEvent.KEYCODE_A, KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_FUNCTION_ON or KeyEvent.META_NUM_LOCK_ON)
        assertEquals(KeyEvent.META_FUNCTION_ON, c.meta)
    }

    @Test fun conflictNamesTheOtherAction() {
        assertEquals(KeyAction.NEXT, d.conflict(KeyAction.LIKE, k(KeyEvent.KEYCODE_N)))
        assertNull(d.conflict(KeyAction.NEXT, k(KeyEvent.KEYCODE_N)))
        assertNull(d.conflict(KeyAction.LIKE, k(KeyEvent.KEYCODE_Z)))
    }

    @Test fun bindWithSwapGivesTheOldKeyAway() {
        val m = d.bind(KeyAction.LIKE, k(KeyEvent.KEYCODE_N))
        assertEquals(listOf(k(KeyEvent.KEYCODE_N)), m.keysFor(KeyAction.LIKE))
        assertEquals(listOf(k(KeyEvent.KEYCODE_F)), m.keysFor(KeyAction.NEXT))
    }

    @Test fun bindWithoutSwapLeavesTheOtherActionUnbound() {
        val m = d.bind(KeyAction.LIKE, k(KeyEvent.KEYCODE_N), swap = false)
        assertTrue(m.keysFor(KeyAction.NEXT).isEmpty())
        assertNull(m.lookup(k(KeyEvent.KEYCODE_F)))
    }

    @Test fun resetOneAndSkipTakenKeys() {
        val m = d.bind(KeyAction.PLAY_PAUSE, k(KeyEvent.KEYCODE_Z)).bind(KeyAction.LIKE, k(KeyEvent.KEYCODE_K), swap = false)
        val r = m.reset(KeyAction.PLAY_PAUSE)
        assertEquals(listOf(k(KeyEvent.KEYCODE_SPACE)), r.keysFor(KeyAction.PLAY_PAUSE))
        assertEquals(KeyAction.LIKE, r.lookup(k(KeyEvent.KEYCODE_K)))
        assertNull(r.lookup(k(KeyEvent.KEYCODE_Z)))
    }

    @Test fun encodeDecodeRoundTrip() {
        val m = d.bind(KeyAction.QUEUE, k(289, KeyEvent.META_FUNCTION_ON)).unbind(KeyAction.TAB_3)
        assertEquals(m, KeyMap.decode(m.encode()))
        assertEquals(KeyMap.DEFAULT, KeyMap.decode(null))
        assertTrue(KeyMap.decode("").map.isEmpty())
        assertEquals(mapOf(k(42) to KeyAction.NEXT), KeyMap.decode("42:0=NEXT;junk;1:x=PLAY_PAUSE;43:0=GONE").map)
    }

    @Test fun labels() {
        assertEquals("Shift+Space", keyLabel(k(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON), "KEYCODE_SPACE"))
        assertEquals("Alt+1", keyLabel(k(KeyEvent.KEYCODE_1, KeyEvent.META_ALT_ON), "KEYCODE_1"))
        assertEquals("Right", keyLabel(k(KeyEvent.KEYCODE_DPAD_RIGHT), "KEYCODE_DPAD_RIGHT"))
        assertEquals("Fn+Key 300", keyLabel(k(300, KeyEvent.META_FUNCTION_ON), "300"))
        assertEquals("Media play pause", cleanKeyName(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "KEYCODE_MEDIA_PLAY_PAUSE"))
    }
}
