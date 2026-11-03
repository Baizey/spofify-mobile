package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.CatalogApi
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native recipe editor. Its parent owns the JSON draft and must gate preview/save with
 * [recipeValidationError]. This bounded Column intentionally does not nest a scrolling list
 * inside the parent's LazyColumn. Catalog reads are limited to typed label lookups and
 * explicit saved-music picker searches; rendering never evaluates a recipe.
 */
@Composable
fun RecipeBuilder(
    definition: JsonObject,
    onChange: (JsonObject) -> Unit,
    playlists: List<Pair<String, String>>,
    client: SpofifyClient,
    enabled: Boolean = true,
) {
    val labels = remember(client) { mutableStateMapOf<String, String>() }
    val catalog = remember(client) { CatalogApi(client) }
    val labelAuthority = remember(client) { RecipeAuthority() }
    val attemptedLabels = remember(client) { mutableSetOf<String>() }
    val references = recipeMusicReferences(definition)
    DisposableEffect(client, references) { onDispose { labelAuthority.invalidate() } }
    LaunchedEffect(client, references) {
        val activeKeys = references.mapTo(mutableSetOf()) { (kind, id) -> "$kind:$id" }
        labels.keys.retainAll(activeKeys)
        attemptedLabels.retainAll(activeKeys)
        val ticket = labelAuthority.begin()
        val queue = references.filter { (kind, id) ->
            val identity = "$kind:$id"
            identity !in attemptedLabels && identity !in labels
        }.iterator()
        coroutineScope {
            repeat(2) {
                launch {
                    while (queue.hasNext()) {
                        val (kind, id) = queue.next()
                        val identity = "$kind:$id"
                        attemptedLabels.add(identity)
                        val label = try {
                            val item = catalog.detail(kind, id)
                            item.name.takeIf { item.kind == kind && item.id == id && it.isNotBlank() }
                        } catch (cancelled: CancellationException) {
                            // A new reference set may still need this cancelled lookup.
                            attemptedLabels.remove(identity)
                            throw cancelled
                        } catch (_: Exception) { null }
                        if (labelAuthority.isCurrent(ticket) && labels[identity] == null) {
                            // Explicit picker selections are already in labels and supersede lookup reads.
                            labels[identity] = label ?: "${kind.replaceFirstChar { it.uppercase() }} unavailable"
                        }
                    }
                }
            }
        }
        labelAuthority.complete(ticket)
    }
    val sources = (definition["sources"] as? JsonArray)?.toList().orEmpty()
    val leafCount = recipePredicateLeafCount(definition)
    var advanced by rememberSaveable { mutableStateOf(false) }
    var recap by rememberSaveable { mutableStateOf(false) }
    fun setSources(items: List<kotlinx.serialization.json.JsonElement>) =
        onChange(definition.withField("sources", JsonArray(items)))

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose music", style = MaterialTheme.typography.titleMedium)
        Text("Collections are joined in the order below. Collection windows are applied before conditions; the final size is applied afterwards.")
        sources.take(4).forEachIndexed { index, element ->
            val source = element as? JsonObject
            key(source?.text("slotId") ?: "invalid-$index") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Source ${index + 1}", style = MaterialTheme.typography.titleSmall)
                    if (source != null) {
                        CollectionPicker("Music from", source, playlists, true, enabled) { choice ->
                            setSources(sources.toMutableList().apply { set(index, changeRecipeSource(source, choice)) })
                        }
                        var windowOpen by rememberSaveable { mutableStateOf("take" in source) }
                        TextButton(onClick = { windowOpen = !windowOpen }) {
                            Text(if (windowOpen) "Hide collection window" else "Limit songs from this collection")
                        }
                        if (windowOpen) TakeEditor(source, "Collection window (before conditions)", enabled) { next ->
                            setSources(sources.toMutableList().apply { set(index, next) })
                        }
                    } else Text("Unsupported source. Remove it and choose a new source.")
                    Row {
                        TextButton(enabled = enabled && index > 0, onClick = {
                            setSources(sources.toMutableList().apply { val item = removeAt(index); add(index - 1, item) })
                        }) { Text("Move source ${index + 1} up") }
                        TextButton(enabled = enabled && index < sources.lastIndex, onClick = {
                            setSources(sources.toMutableList().apply { val item = removeAt(index); add(index + 1, item) })
                        }) { Text("Move source ${index + 1} down") }
                    }
                    TextButton(enabled = enabled && sources.size > 1, onClick = {
                        setSources(sources.toMutableList().apply { removeAt(index) })
                    }) { Text("Remove source ${index + 1}") }
                }
                HorizontalDivider()
            }
        }
        OutlinedButton(enabled = enabled && sources.size < 4, onClick = { setSources(sources + newRecipeSource()) }) {
            Text("Add source")
        }
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Hide conditions, order and size" else "Only keep, order and size (advanced)")
        }
        if (advanced) {
            listOf("include" to "Keep matching", "exclude" to "Exclude matching").forEach { (field, title) ->
                key(field) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    val predicate = definition[field] as? JsonObject
                    if (predicate == null) {
                        Text(if (field == "include") "All music passes this stage." else "No exclusion.")
                        TextButton(enabled = enabled && leafCount < 32, onClick = {
                            onChange(definition.withField(field, initialRecipePredicate("genre")))
                        }) { Text("Add $field condition") }
                    } else {
                        if (leafCount <= 32) {
                            PredicateEditor(predicate, 1, playlists, client, labels, enabled, leafCount < 32) {
                                onChange(definition.withField(field, it))
                            }
                        } else Text("Conditions exceed local size/depth limits. Clear a group to edit safely.")
                        TextButton(enabled = enabled, onClick = { onChange(definition.withField(field, null)) }) {
                            Text("Clear $field conditions")
                        }
                    }
                }
            }
            RecipeChoice("Duplicates", listOf("unique" to "One occurrence per track", "keep" to "Keep duplicate occurrences"),
                definition.text("duplicates"), enabled) { onChange(definition.withField("duplicates", JsonPrimitive(it))) }
            RecipeChoice("Result order", listOf("sourceOrder" to "Source order", "title" to "Title (ASCII case-insensitive)"),
                definition.text("sort"), enabled) { onChange(definition.withField("sort", JsonPrimitive(it))) }
            TakeEditor(definition, "Playlist size (after conditions and order)", enabled, onChange)
            Text("Genres use the first nonempty tier: track labels, canonical-album labels, then cached credited-artist labels. Missing genres are unknown. Not jazz and excluding jazz both omit unknowns; use Any of with Unknown genre to include them.",
                style = MaterialTheme.typography.bodySmall)
        }
        recipeValidationError(definition)?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Text("Local checks do not verify references or playlist cycles. Preview and save validate the complete graph on the server.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { recap = !recap }) {
            Text(if (recap) "Hide how your music is chosen" else "How your music is chosen")
        }
        if (recap) {
            sources.take(4).forEachIndexed { index, element ->
                val source = element as? JsonObject
                Text("${index + 1}. ${sourceLabel(source, playlists)} → ${windowLabel(source?.get("take") as? JsonObject)}")
            }
            Text("Only keep: ${predicateLabel(definition["include"] as? JsonObject, playlists, labels)}")
            if ("exclude" in definition) Text("Exclude: ${predicateLabel(definition["exclude"] as? JsonObject, playlists, labels)}")
            Text(if (definition.text("duplicates") == "keep") "Keep duplicate occurrences" else "Keep the first surviving occurrence of each track")
            Text(if (definition.text("sort") == "title") "Order by title (ASCII case-insensitive)" else "Keep source order")
            Text("Playlist size: ${windowLabel(definition["take"] as? JsonObject)}")
        }
    }
}

@Composable
private fun RecipeChoice(
    label: String,
    choices: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
            Text(choices.firstOrNull { it.first == selected }?.second ?: "Choose an option")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun CollectionPicker(
    label: String,
    source: JsonObject,
    playlists: List<Pair<String, String>>,
    allowAllSaved: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val selected = if (source.text("kind") == "playlist") "playlist:${source.text("playlistId").orEmpty()}" else source.text("kind")
    val choices = buildList {
        add("likes" to "Liked songs (newest first)")
        if (allowAllSaved) add("allSaved" to "All saved music (title order)")
        addAll(playlists.map { "playlist:${it.first}" to it.second })
        if (source.text("kind") == "playlist" && none { it.first == selected }) {
            add(selected.orEmpty() to "Saved collection (not loaded)")
        }
    }
    RecipeChoice(label, choices, selected, enabled, onSelect)
}

@Composable
private fun TakeEditor(owner: JsonObject, title: String, enabled: Boolean, onChange: (JsonObject) -> Unit) {
    val take = owner["take"] as? JsonObject
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecipeChoice(title, listOf("all" to "All matches (no limit)", "first" to "First N", "last" to "Last N"),
            if ("take" !in owner) "all" else take?.text("side"), enabled) { side ->
            onChange(owner.withField("take", if (side == "all") null else buildJsonObject {
                put("side", side)
                put("count", take?.get("count") ?: JsonPrimitive(25))
            }))
        }
        if (take != null) {
            val count = take["count"] as? JsonPrimitive
            OutlinedTextField(
                value = count?.content.orEmpty(),
                onValueChange = { onChange(owner.withField("take", take.withField("count", recipeTakeCount(it)))) },
                label = { Text("Number of songs — $title") },
                enabled = enabled,
                singleLine = true,
                isError = count == null || count.isString || count.content.toLongOrNull()?.let { it in 1..Int.MAX_VALUE.toLong() } != true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("First / last keeps forward order; it never reverses the songs.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PredicateEditor(
    value: JsonObject,
    depth: Int,
    playlists: List<Pair<String, String>>,
    client: SpofifyClient,
    labels: MutableMap<String, String>,
    enabled: Boolean,
    canAddLeaf: Boolean,
    replace: (JsonObject) -> Unit,
) {
    if (depth > 4) {
        Text("Condition exceeds four levels. Change or clear the parent condition.", color = MaterialTheme.colorScheme.error)
        return
    }
    val op = value.text("op")
    val operations = buildList {
        addAll(listOf("genre" to "Genre equals", "genreKnown" to "Genre known / unknown", "availability" to "Recorded audio",
            "membership" to "Collection membership", "artist" to "Artist", "album" to "Album"))
        if (depth < 4) addAll(listOf("all" to "All of (AND)", "any" to "Any of (OR)", "not" to "Not"))
    }
    Column(Modifier.padding(start = if (depth > 1) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecipeChoice("Condition", operations, op, enabled) { replace(initialRecipePredicate(it)) }
        key(op) {
            when (op) {
                "all", "any" -> {
                    val args = (value["args"] as? JsonArray)?.toList().orEmpty()
                    args.take(32).forEachIndexed { index, child ->
                        val predicate = child as? JsonObject
                        if (predicate != null) PredicateEditor(predicate, depth + 1, playlists, client, labels, enabled, canAddLeaf) { next ->
                            replace(value.withField("args", JsonArray(args.toMutableList().apply { set(index, next) })))
                        } else Text("Unsupported condition")
                        TextButton(enabled = enabled && args.size > 1, onClick = {
                            replace(value.withField("args", JsonArray(args.toMutableList().apply { removeAt(index) })))
                        }) { Text("Remove condition ${index + 1}") }
                    }
                    TextButton(enabled = enabled && canAddLeaf && args.size < 32, onClick = {
                        replace(value.withField("args", JsonArray(args + initialRecipePredicate("genre"))))
                    }) { Text("Add condition") }
                }
                "not" -> {
                    val child = value["arg"] as? JsonObject
                    if (child != null) PredicateEditor(child, depth + 1, playlists, client, labels, enabled, canAddLeaf) { replace(value.withField("arg", it)) }
                    else Text("Invalid negated condition; choose the condition again.")
                }
                "genre" -> OutlinedTextField(value.text("value").orEmpty(), { replace(value.withField("value", JsonPrimitive(it))) },
                    label = { Text("Exact genre label") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                "genreKnown" -> RecipeChoice("Knownness", listOf("unknown" to "Unknown genre", "known" to "Known genre"),
                    when ((value["value"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) {
                        true -> "known"; false -> "unknown"; null -> null
                    }, enabled) { replace(value.withField("value", JsonPrimitive(it == "known"))) }
                "availability" -> RecipeChoice("Audio", listOf("available" to "Has recorded audio", "unavailable" to "No recorded audio"),
                    value.text("value"), enabled) { replace(value.withField("value", JsonPrimitive(it))) }
                "membership" -> CollectionPicker("Member of", value["source"] as? JsonObject ?: JsonObject(emptyMap()), playlists, false, enabled) {
                    replace(value.withField("source", changeRecipeSource(JsonObject(emptyMap()), it)))
                }
                "artist", "album" -> SavedMusicPicker(op, value.text("${op}Id").orEmpty(), client, labels, enabled) {
                    replace(value.withField("${op}Id", JsonPrimitive(it.id)))
                }
            }
        }
    }
}

@Composable
private fun SavedMusicPicker(
    kind: String,
    selectedId: String,
    client: SpofifyClient,
    labels: MutableMap<String, String>,
    enabled: Boolean,
    onSelect: (LibraryItem) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val authority = remember(client, kind) { RecipeAuthority() }
    val scope = rememberCoroutineScope()
    fun invalidate() {
        authority.invalidate()
        job?.cancel()
        job = null
        pending = false
        results = emptyList()
        status = ""
    }
    DisposableEffect(client, kind, selectedId, enabled) {
        onDispose { invalidate() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (selectedId.isEmpty()) "No $kind selected" else labels["$kind:$selectedId"] ?: "${kind.replaceFirstChar { it.uppercase() }} unavailable")
        TextButton(enabled = enabled, onClick = { invalidate(); open = !open }) {
            Text(if (open) "Close $kind picker" else "Choose $kind")
        }
        if (open) {
            OutlinedTextField(query, { invalidate(); query = it }, label = { Text("Search saved ${kind}s") },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = enabled && !pending, onClick = {
                invalidate()
                val ticket = authority.begin()
                pending = true
                status = "Searching saved music…"
                job = scope.launch {
                    try {
                        val page = CatalogApi(client).browse(kind = "${kind}s", query = query, availability = "all", limit = 40)
                        if (!authority.isCurrent(ticket)) return@launch
                        results = page.items.filter { it.kind == kind && it.id.isNotEmpty() && it.name.isNotBlank() }.take(40)
                        status = when {
                            results.isEmpty() -> "No saved matches."
                            page.total > results.size -> "Showing ${results.size} of ${page.total}. Refine the search to find another $kind."
                            else -> "${results.size} saved matches."
                        }
                        authority.complete(ticket)
                        pending = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (authority.fail(ticket)) {
                            pending = false
                            status = "Saved music could not load. Search again explicitly."
                        }
                    }
                }
            }) { Text("Search saved music") }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            results.forEach { item ->
                TextButton(enabled = enabled, onClick = {
                    invalidate()
                    labels["$kind:${item.id}"] = item.name
                    open = false
                    onSelect(item)
                }) { Text(item.name) }
            }
        }
    }
}

private fun sourceLabel(source: JsonObject?, playlists: List<Pair<String, String>>): String = when (source?.text("kind")) {
    "likes" -> "Liked songs (newest first)"
    "allSaved" -> "All saved music (title order)"
    "playlist" -> playlists.firstOrNull { it.first == source.text("playlistId") }?.second ?: "Saved collection (not loaded)"
    else -> "Unsupported source"
}

private fun windowLabel(take: JsonObject?): String = if (take == null) "All matches (no limit)" else {
    val count = (take["count"] as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: "invalid size"
    "${if (take.text("side") == "last") "Last" else "First"} $count, retaining forward order"
}

private fun predicateLabel(value: JsonObject?, playlists: List<Pair<String, String>>, labels: Map<String, String>, depth: Int = 1): String {
    if (value == null) return "All matches"
    if (depth > 4) return "Unsupported condition depth"
    return when (val op = value.text("op")) {
        "all", "any" -> "${if (op == "all") "All" else "Any"} of (${(value["args"] as? JsonArray)?.take(32)?.joinToString("; ") { predicateLabel(it as? JsonObject, playlists, labels, depth + 1) }.orEmpty()})"
        "not" -> "Not (${predicateLabel(value["arg"] as? JsonObject, playlists, labels, depth + 1)})"
        "genre" -> "Genre is “${value.text("value").orEmpty()}”"
        "genreKnown" -> if ((value["value"] as? JsonPrimitive)?.booleanOrNull == true) "Genre is known" else "Genre is unknown"
        "availability" -> if (value.text("value") == "available") "Has recorded audio" else "No recorded audio"
        "membership" -> "In ${sourceLabel(value["source"] as? JsonObject, playlists)}"
        "artist", "album" -> "${op.replaceFirstChar { it.uppercase() }}: ${labels["$op:${value.text("${op}Id")}"] ?: "${op.replaceFirstChar { it.uppercase() }} unavailable"}"
        else -> "Unsupported condition"
    }
}
