package dev.spofify.mobile

/** Recent is a bounded shelf, not an offset-paginated endpoint. */
internal fun displayedTotal(page: Page, returned: Int, total: Int): Int = if (page == Page.Home) returned else total
