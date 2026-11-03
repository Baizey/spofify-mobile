package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalPlaylistSummary
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateTest {
    private class Reader : HomeReader {
        var recentCalls = 0; var likeCalls = 0; var playlistCalls = 0
        var recentResult = CompletableDeferred<List<LibraryItem>>()
        var likesResult = CompletableDeferred<Int>()
        var playlistsResult = CompletableDeferred<List<PersonalPlaylistSummary>>()
        var revision = 1L
        override suspend fun catalogRevision() = revision
        override suspend fun recent(): List<LibraryItem> { recentCalls++; return recentResult.await() }
        override suspend fun likedCount(): Int { likeCalls++; return likesResult.await() }
        override suspend fun playlists(): List<PersonalPlaylistSummary> { playlistCalls++; return playlistsResult.await() }
    }

    @Test fun slowFailedPlaylistsCannotBlankRecentOrLikedCountAndRecoveryIsIndependent() = runTest {
        val reader = Reader()
        val holder = HomeStateHolder(reader, backgroundScope)
        holder.setVisible(true); runCurrent()
        reader.recentResult.complete(List(12) { LibraryItem("track", "$it", "Song $it") })
        reader.likesResult.complete(123); runCurrent()
        assertEquals(8, holder.state.value.recent.value?.size)
        assertEquals(123, holder.state.value.likedCount.value)
        assertTrue(holder.state.value.playlists.loading)
        reader.playlistsResult.completeExceptionally(IOException()); runCurrent()
        assertNotNull(holder.state.value.playlists.error)
        assertEquals(8, holder.state.value.recent.value?.size)
        reader.playlistsResult = CompletableDeferred(emptyList())
        holder.refresh("playlists"); runCurrent()
        assertEquals(1, reader.recentCalls); assertEquals(1, reader.likeCalls); assertEquals(2, reader.playlistCalls)
        assertEquals(emptyList<PersonalPlaylistSummary>(), holder.state.value.playlists.value)
        holder.setVisible(false)
    }

    @Test fun firstCatalogFenceValidatesOlderShelvesAndQuietFailuresRetryOnLaterSameRevision() = runTest {
        val reader = Reader()
        val holder = HomeStateHolder(reader, backgroundScope)
        holder.setVisible(true); runCurrent()
        holder.checkCatalogRevision(); runCurrent()
        val oldRecent = reader.recentResult
        reader.recentResult = CompletableDeferred(listOf(LibraryItem("track", "new", "New")))
        oldRecent.complete(listOf(LibraryItem("track", "old", "Old")))
        reader.likesResult.complete(3); reader.playlistsResult.complete(emptyList()); runCurrent()
        assertEquals("new", holder.state.value.recent.value?.first()?.id)
        assertEquals(2, reader.recentCalls)
        reader.revision = 2
        reader.recentResult = CompletableDeferred()
        holder.checkCatalogRevision(); runCurrent()
        reader.recentResult.completeExceptionally(IOException()); runCurrent()
        assertEquals("new", holder.state.value.recent.value?.first()?.id)
        val likes = reader.likeCalls; val playlists = reader.playlistCalls
        reader.recentResult = CompletableDeferred(listOf(LibraryItem("track", "latest", "Latest")))
        holder.checkCatalogRevision(); runCurrent()
        assertEquals(4, reader.recentCalls)
        assertEquals(likes, reader.likeCalls); assertEquals(playlists, reader.playlistCalls)
        assertEquals("latest", holder.state.value.recent.value?.first()?.id)
        holder.setVisible(false)
    }

    @Test fun targetedInvalidationRetainsUsableContentAndQueuesOnlyOneTrailingRead() = runTest {
        val reader = Reader().apply {
            recentResult.complete(listOf(LibraryItem("track", "first", "First")))
            likesResult.complete(2); playlistsResult.complete(emptyList())
        }
        val holder = HomeStateHolder(reader, backgroundScope)
        holder.setVisible(true); runCurrent()
        reader.recentResult = CompletableDeferred()
        holder.invalidate("recent"); runCurrent()
        assertEquals("first", holder.state.value.recent.value?.first()?.id)
        holder.invalidate("recent"); holder.invalidate("recent"); runCurrent()
        assertEquals(2, reader.recentCalls)
        reader.recentResult.complete(listOf(LibraryItem("track", "new", "New"))); runCurrent()
        assertEquals(3, reader.recentCalls)
        assertEquals(1, reader.likeCalls); assertEquals(1, reader.playlistCalls)
        holder.setVisible(false)
        holder.invalidate("likes"); runCurrent()
        assertEquals(1, reader.likeCalls)
        holder.setVisible(true); runCurrent()
        assertEquals(2, reader.likeCalls)
        holder.setVisible(false)
    }
}
