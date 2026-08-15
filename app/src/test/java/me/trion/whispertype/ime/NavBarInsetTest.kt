package me.trion.whispertype.ime

import org.junit.Assert.*
import org.junit.Test

class NavBarInsetTest {

    @Test
    fun `zero system inset keeps the layout padding`() {
        assertEquals(6, NavBarInset.bottomPadding(basePaddingPx = 6, systemInsetPx = 0))
    }

    @Test
    fun `gesture bar inset is added on top of the layout padding`() {
        assertEquals(54, NavBarInset.bottomPadding(basePaddingPx = 6, systemInsetPx = 48))
    }

    @Test
    fun `negative or missing inset is treated as zero`() {
        assertEquals(6, NavBarInset.bottomPadding(basePaddingPx = 6, systemInsetPx = -12))
    }
}
