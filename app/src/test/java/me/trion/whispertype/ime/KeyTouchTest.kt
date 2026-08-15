package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class KeyTouchTest {

    @Test
    fun `gutter between two keys belongs to a neighbor`() {
        val width = 100
        val gutter = 6
        // Old layout: key0 [6,44), dead [44,56), key1 [56,94)
        assertEquals(0, KeyTouch.hitIndex(10f, 2, width, gutter))
        assertEquals(0, KeyTouch.hitIndex(49f, 2, width, gutter))
        assertEquals(1, KeyTouch.hitIndex(50f, 2, width, gutter))
        assertEquals(1, KeyTouch.hitIndex(90f, 2, width, gutter))
    }

    @Test
    fun `ten letter keys have no dead strip in the middle`() {
        val width = 1080
        val gutter = 0
        val midOfGapBetween3and4 = KeyTouch.keyRight(3, 10, width, gutter).toFloat()
        val left = KeyTouch.hitIndex(midOfGapBetween3and4 - 0.5f, 10, width, gutter)
        val right = KeyTouch.hitIndex(midOfGapBetween3and4, 10, width, gutter)
        assertEquals(3, left)
        assertEquals(4, right)
        assertTrue(left != right)
    }

    @Test
    fun `empty or zero-size row misses`() {
        assertEquals(-1, KeyTouch.hitIndex(10f, 0, 100, 0))
        assertEquals(-1, KeyTouch.hitIndex(10f, 5, 0, 0))
    }

    @Test
    fun `insideView rejects the old outside-bounds cancel`() {
        assertTrue(KeyTouch.insideView(1f, 1f, 40, 48))
        assertFalse(KeyTouch.insideView(-1f, 10f, 40, 48))
        assertFalse(KeyTouch.insideView(40f, 10f, 40, 48))
    }
}
