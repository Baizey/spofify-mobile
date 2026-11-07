package dev.spofify.mobile.feature.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Pure resident projection: no API/queue owner, index remapping, deduplication or source mutation. */
internal fun <T> filterPlaylistEntries(entries: List<T>, query: String,
    ensureCurrent: () -> Unit = {}, track: (T) -> LibraryItem): List<T> {
    val literal = query.trim()
    if (literal.isEmpty()) return entries
    return entries.filter { entry ->
        ensureCurrent()
        val item = track(entry)
        buildString {
            append(item.name)
            append(' ')
            append(item.artists.joinToString(", ") { it.name.trim() })
            append(' ')
            append(item.albumName.orEmpty())
        }.contains(literal, ignoreCase = true)
    }
}

internal class ResidentPlaylistProjection<T>(val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    internal data class Result<T>(val source: List<T>, val query: String, val items: List<T>)
    var result by mutableStateOf<Result<T>?>(null)
}

/** Session-local result survives route disposal; each source/query incarnation owns publication. */
@Composable
internal fun <T> residentPlaylistProjection(memory: ResidentPlaylistProjection<T>, entries: List<T>, query: String,
    track: (T) -> LibraryItem): List<T>? {
    if (query.trim().isEmpty()) return entries
    val cached = memory.result
    val current = cached?.takeIf { it.source === entries && it.query == query }
    LaunchedEffect(entries, query) {
        if (current == null) {
            val items = withContext(memory.dispatcher) {
                filterPlaylistEntries(entries, query, ensureCurrent = { ensureActive() }, track = track)
            }
            memory.result = ResidentPlaylistProjection.Result(entries, query, items)
        }
    }
    return current?.items
}

internal fun playlistFilterCoverage(resident: Int, total: Long?, matching: Int, active: Boolean): String {
    val partial = total == null || resident.toLong() < total
    return when {
        partial && active -> "$matching matches in $resident loaded songs · not the whole playlist"
        partial -> "Filters $resident loaded songs · not the whole playlist"
        active -> "$matching of $resident songs"
        else -> "Filters all $resident songs"
    }
}

/** Local input only: no global Search activation, navigation reset, query grammar or submit read. */
@Composable
internal fun PlaylistFilterField(query: String, modifier: Modifier = Modifier,
    onEditingChanged: (Boolean) -> Unit = {}, onQueryChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val editingChanged by rememberUpdatedState(onEditingChanged)
    DisposableEffect(Unit) { onDispose { editingChanged(false) } }
    var requestingRest by remember { mutableStateOf(false) }
    var resting by remember { mutableStateOf(false) }
    val restingFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeShownForFocus by remember { mutableStateOf(false) }
    fun endEditing() {
        requestingRest = true
        try { restingFocus.requestFocus(FocusDirection.Exit) }
        finally { requestingRest = false }
        keyboard?.hide()
    }
    LaunchedEffect(editing, imeVisible) {
        if (!editing) imeShownForFocus = false
        else if (imeVisible) imeShownForFocus = true
        else if (imeShownForFocus) endEditing()
    }
    // Android IME owns its visible Back; when it hides we end editing without a second Back.
    // Hardware-keyboard Back still ends editing before any route pop.
    BackHandler(editing && !imeVisible) { endEditing() }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(colors.background)
        .focusRequester(restingFocus).onFocusChanged { resting = it.isFocused }
        .focusProperties {
            canFocus = requestingRest || resting
            onExit = { if (requestedFocusDirection == FocusDirection.Exit && editing) endEditing() }
        }.focusTarget()) {
        BasicTextField(query, onValueChange = onQueryChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { endEditing() }),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playlist-filter")
                .onFocusChanged { editing = it.isFocused; editingChanged(it.isFocused) }
                .semantics { contentDescription = "Filter playlist" },
            decorationBox = { input ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
                    val thickness = (if (editing) 2.dp else 1.dp).toPx()
                    drawLine(if (editing) colors.primary else colors.outlineVariant,
                        Offset(0f, size.height - thickness / 2), Offset(size.width, size.height - thickness / 2), thickness)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Search, null, tint = colors.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (query.isEmpty()) Text("Find in this playlist", style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Clear playlist filter")
                    } else Spacer(Modifier.width(48.dp))
                }
            })
    }
}
