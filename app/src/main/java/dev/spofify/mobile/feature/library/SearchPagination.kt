package dev.spofify.mobile.feature.library

private const val SEARCH_PREFETCH_ROWS = 5

/** Index zero is the best result at the bottom; the highest visible index approaches the weak end. */
internal fun shouldLoadNextSearchPage(state: SearchState, lastVisibleIndex: Int, layoutItemCount: Int): Boolean {
    if (state.paused || !state.loaded || state.query.isBlank() || state.items.isEmpty() || !state.hasMore ||
        state.busy || state.error != null) return false
    // Every nonblank query keeps one status footer, even when that footer has no visible content.
    if (layoutItemCount != state.items.size + 1 || lastVisibleIndex !in 0 until layoutItemCount) return false
    val lastIndex = layoutItemCount - 1
    return lastVisibleIndex >= (lastIndex - SEARCH_PREFETCH_ROWS).coerceAtLeast(0)
}
