package me.trion.whispertype.ime

/**
 * Pure hit-testing for IME keys. The controller uses this so fat-finger
 * taps in the old inter-key gutter still resolve to a neighbor, and so
 * popup keys can commit on ACTION_DOWN without Android click slop.
 */
object KeyTouch {
    /** Horizontal gap that used to be dead LayoutParams margin on each side. */
    const val LEGACY_GUTTER_DP = 3

    fun keyLeft(index: Int, count: Int, rowWidth: Int, gutterPx: Int): Int {
        if (count <= 0 || rowWidth <= 0) return 0
        val usable = (rowWidth - gutterPx * 2).coerceAtLeast(0)
        return gutterPx + (usable * index) / count
    }

    fun keyRight(index: Int, count: Int, rowWidth: Int, gutterPx: Int): Int {
        if (count <= 0 || rowWidth <= 0) return 0
        return keyLeft(index + 1, count, rowWidth, gutterPx)
    }

    /**
     * Map an x in row coordinates to a key index.
     * [gutterPx] is leftover visual inset at each end of the row; the
     * space *between* keys is part of the nearer key.
     */
    fun hitIndex(x: Float, count: Int, rowWidth: Int, gutterPx: Int): Int {
        if (count <= 0 || rowWidth <= 0) return -1
        val clamped = x.coerceIn(0f, rowWidth.toFloat())
        for (i in 0 until count) {
            val left = keyLeft(i, count, rowWidth, gutterPx)
            val right = keyRight(i, count, rowWidth, gutterPx)
            if (clamped >= left && clamped < right) return i
        }
        return count - 1
    }

    fun insideView(x: Float, y: Float, width: Int, height: Int): Boolean {
        return x >= 0f && y >= 0f && x < width && y < height
    }
}
