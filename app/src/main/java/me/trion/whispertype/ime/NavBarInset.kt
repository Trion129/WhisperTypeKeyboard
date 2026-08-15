package me.trion.whispertype.ime

/**
 * Extra bottom padding so the IME clears a gesture / 3-button nav bar
 * only when the system actually reports one. Phones whose IME window
 * already sits above the bar send 0 and keep the layout's own padding.
 */
object NavBarInset {
    fun bottomPadding(basePaddingPx: Int, systemInsetPx: Int): Int {
        return basePaddingPx + systemInsetPx.coerceAtLeast(0)
    }
}
