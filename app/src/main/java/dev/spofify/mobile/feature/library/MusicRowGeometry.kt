package dev.spofify.mobile.feature.library

/** Logical dp shared by listening rows and collection controls; gutters belong inside the row hit area. */
internal data class MusicRowGeometry(val numbered: Boolean = false, val gutter: Int = EDGE_GUTTER) {
    val leadingWidth: Int get() = if (numbered) NUMBER_WIDTH + NUMBER_GAP else 0
    val identityGap: Int get() = if (numbered) NUMBER_GAP else IDENTITY_GAP
    val artworkStart: Int get() = gutter + leadingWidth
    fun moreStart(width: Int): Int = width - gutter - ACTION_SLOT
    fun identityWidth(width: Int, reservePopularity: Boolean = false): Int =
        (moreStart(width) - artworkStart - ARTWORK_SLOT - identityGap -
            (if (reservePopularity) POPULARITY_SLOT else 0)).coerceAtLeast(0)
    fun swipeEndExclusion(hasMore: Boolean): Int = if (hasMore) gutter + ACTION_SLOT else 0

    companion object {
        const val EDGE_GUTTER = 20
        const val ARTWORK_SLOT = 48
        const val ACTION_SLOT = 48
        const val NUMBER_WIDTH = 22
        const val NUMBER_GAP = 10
        const val IDENTITY_GAP = 13
        const val POPULARITY_SLOT = 24
        const val VERTICAL_PADDING = 4
        // The artwork and action slots fit at normal text size; scaled text can grow the row.
        const val MIN_HEIGHT = ACTION_SLOT + 2 * VERTICAL_PADDING
    }
}
