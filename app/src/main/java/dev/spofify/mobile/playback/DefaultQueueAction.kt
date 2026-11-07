package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem

/** UI intent default, not authority: the receiver resolves and admits the captured selection. */
internal fun LibraryItem.defaultQueueAction(): PlaybackListAction =
    if (kind == "track") PlaybackListAction.QUEUE_HIGH else PlaybackListAction.QUEUE_LOW
