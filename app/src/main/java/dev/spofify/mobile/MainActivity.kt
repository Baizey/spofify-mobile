package dev.spofify.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackState
import java.util.Locale

private val Mint = Color(0xFF78E0AC)
private val Background = Color(0xFF101412)
private val Surface = Color(0xFF1A211D)

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF08351F),
                background = Background, surface = Surface, secondary = Mint, surfaceContainer = Surface)) {
                SpofifyApp(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpofifyApp(model: LibraryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val player by model.playback.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
    }
    LaunchedEffect(state.session) { if (state.session == null) expanded = false }
    BackHandler(state.canGoBack && !expanded && state.session != null) { model.back() }

    if (state.restoring) {
        Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
        return
    }
    if (state.session == null) {
        SignIn(state, model::signIn, model::reconnect)
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(title = { Text("spofify", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (state.canGoBack) IconButton(onClick = model::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }, actions = {
                    IconButton(onClick = { account = true }) { Icon(Icons.Default.AccountCircle, "Account and server") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Background))
        },
        bottomBar = {
            Column {
                player.item?.let { item ->
                    MiniPlayer(item, player, model, onExpand = { expanded = true })
                }
                NavigationBar(containerColor = Background) {
                    listOf(Tab.HOME to Icons.Default.Home, Tab.SEARCH to Icons.Default.Search,
                        Tab.LIBRARY to Icons.Default.LibraryMusic).forEach { (tab, icon) ->
                        NavigationBarItem(selected = state.tab == tab, onClick = { model.switchTab(tab) },
                            icon = { Icon(icon, null) }, label = { Text(tab.name.lowercase().replaceFirstChar { it.titlecase() }) })
                    }
                }
            }
        },
        containerColor = Background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LibraryContent(state, model, Modifier.widthIn(max = 760.dp).fillMaxSize())
        }
    }
    if (account) AlertDialog(onDismissRequest = { account = false }, title = { Text("Your connection") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.session?.username.orEmpty(), fontWeight = FontWeight.Bold)
            Text(state.server, style = MaterialTheme.typography.bodyMedium)
            Text("Sign out to change servers. Your library and playlists stay on your server.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !state.busy, onClick = { account = false; model.logout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { account = false }) { Text("Done") } })
    if (expanded && player.item != null) ModalBottomSheet(onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Background) {
        NowPlaying(player, state, model, Modifier.fillMaxWidth().fillMaxHeight(0.94f))
    }
}

@Composable
private fun SignIn(state: LibraryState, signIn: (String, String, String) -> Unit, reconnect: () -> Unit) {
    var server by remember(state.server) { mutableStateOf(state.server) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var acknowledged by remember(server) { mutableStateOf(false) }
    val cleartext = server.trim().startsWith("http://", ignoreCase = true)
    Box(Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(48.dp), tint = Mint)
            Text("Your music.\nYour server.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Connect to Spofify and make yourself at home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(server, { server = it }, label = { Text("Server URL") },
                placeholder = { Text("https://music.example.com") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Text("Use your server's full origin, including its port if needed. No /listen or /api path.", style = MaterialTheme.typography.bodySmall)
            if (cleartext) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it }, enabled = !state.busy)
                    Text("This is a trusted local network. HTTP sends my password and music without encryption.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (showPassword) "Hide password" else "Show password")
                } }, modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.canRetryConnection) OutlinedButton(onClick = reconnect, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Retry saved connection") }
            Button(onClick = { signIn(server, username, password); password = ""; showPassword = false },
                enabled = !state.busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && (!cleartext || acknowledged),
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Connect & listen", fontWeight = FontWeight.Bold)
            }
            Text("Use an existing Spofify account. No Spotify sign-in required.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryContent(state: LibraryState, model: LibraryViewModel, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            when (val page = state.page) {
                Page.Home -> {
                    Text("Made for listening.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Your library, wherever you are.", Modifier.padding(top = 6.dp, bottom = 18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Shortcut("Liked songs", Icons.Default.Favorite, Modifier.weight(1f)) { model.open(Page.Likes) }
                        Shortcut("Playlists", Icons.AutoMirrored.Filled.QueueMusic, Modifier.weight(1f)) { model.open(Page.Playlists) }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Recently played", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                is Page.Search -> SearchField(page.query, model::search)
                is Page.Catalog -> {
                    Text("Your library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("albums" to "Albums", "artists" to "Artists", "tracks" to "Songs").forEach { (kind, title) ->
                            FilterChip(selected = page.kind == kind, onClick = { model.open(Page.Catalog(kind)) }, label = { Text(title) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { model.open(Page.Likes) }) { Text("Liked songs") }
                        TextButton(onClick = { model.open(Page.Playlists) }) { Text("Playlists") }
                    }
                }
                is Page.Detail -> {
                    Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Artwork(page.item, model, Modifier.size(100.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.item.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = Mint)
                            Text(page.item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(page.item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ContextPlayButtons(state.preparing, page.item.playable, model::playPage)
                }
                Page.Likes -> {
                    Text("Liked songs", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    ContextPlayButtons(state.preparing, state.total > 0, model::playPage)
                }
                Page.Playlists -> Text("Your playlists", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                is Page.Playlist -> {
                    Text(page.playlist.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    if (page.playlist.description.isNotBlank()) Text(page.playlist.description, Modifier.padding(vertical = 8.dp))
                    ContextPlayButtons(state.preparing, page.playlist.availableCount > 0, model::playPage)
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        state.error?.let { error -> item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::refresh) { Text("Try again") }
            }
        } }
        if (!state.loading && state.error == null && state.items.isEmpty() && state.playlists.isEmpty()) item {
            val text = when (val page = state.page) {
                Page.Home -> "Your first listen starts here. Explore the music on your server."
                is Page.Search -> if (page.query.isBlank()) "Find artists, albums and songs in your library." else "No matches. Try an artist, album or song name."
                Page.Likes -> "Songs you like will find a home here. Tap the heart in Now Playing."
                Page.Playlists -> "No personal playlists yet. Existing manual playlists from your server appear here."
                else -> "There's no music here yet. Add audio using your server, then come back."
            }
            Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.page == Page.Home) Button(onClick = { model.switchTab(Tab.LIBRARY) }) { Text("Browse albums") }
            }
        }
        itemsIndexed(state.playlists) { _, playlist ->
            Row(Modifier.fillMaxWidth().clickable { model.open(Page.Playlist(playlist)) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(48.dp), tint = Mint)
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${playlist.trackCount} songs · ${playlist.availableCount} available", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        itemsIndexed(state.items) { _, item -> MusicRow(item, model, state.preparing) }
        if (!state.loading && state.offset < state.total && state.error == null) item {
            TextButton(onClick = model::more, modifier = Modifier.fillMaxWidth()) { Text("Show more (${state.offset} of ${state.total})") }
        }
    }
}

@Composable
internal fun SearchField(initial: String, search: (String) -> Unit) {
    OutlinedTextField(initial, { if (it.length <= 200) search(it) }, singleLine = true,
        placeholder = { Text("Artists, albums, songs") }, leadingIcon = { Icon(Icons.Default.Search, null) },
        label = { Text("Search your music") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        trailingIcon = { if (initial.isNotEmpty()) IconButton(onClick = { search("") }) { Icon(Icons.Default.Close, "Clear search") } })
}

@Composable
private fun Shortcut(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(12.dp), color = Surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = Mint)
            Text(title, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ContextPlayButtons(preparing: Boolean, available: Boolean, play: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { play(false) }, enabled = available && !preparing) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        OutlinedButton(onClick = { play(true) }, enabled = available && !preparing) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
        }
        if (preparing) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
    }
}

@Composable
private fun MusicRow(item: LibraryItem, model: LibraryViewModel, preparing: Boolean) {
    Row(Modifier.fillMaxWidth().clickable {
        if (item.kind == "track") { if (item.playable) model.play(item) }
        else model.open(Page.Detail(item))
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(item, model, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (item.playable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (item.playable) item.subtitle else "${item.subtitle} · Unavailable", maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { model.play(item) }, enabled = item.playable && !preparing) {
            Icon(Icons.Default.PlayArrow, "Play ${item.name}", tint = if (item.playable) Mint else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Artwork(item: LibraryItem, model: LibraryViewModel, modifier: Modifier) {
    val loader = model.graph.imageLoader
    val client = model.graph.client
    val hue = ((item.id.hashCode().toLong() and 0x7FFFFFFF) % 360).toFloat()
    Box(modifier.background(Color.hsl(hue, 0.23f, 0.22f)), contentAlignment = Alignment.Center) {
        Text(item.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.headlineLarge,
            color = Color.White.copy(alpha = 0.65f), fontWeight = FontWeight.Bold)
        if (loader != null && client != null) AsyncImage(model = client.artworkUrl(item), imageLoader = loader,
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun MiniPlayer(item: LibraryItem, state: PlaybackState, model: LibraryViewModel, onExpand: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(Surface)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, model, Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Mint)
            }
            IconButton(onClick = model.playback::toggle) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = model.playback::next) { Icon(Icons.Default.SkipNext, "Next song") }
        }
        LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = Color.Transparent)
    }
}

@Composable
private fun NowPlaying(player: PlaybackState, state: LibraryState, model: LibraryViewModel, modifier: Modifier) {
    val item = player.item ?: return
    var queue by remember { mutableStateOf(false) }
    var scrub by remember(item.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(item.id) { model.checkLike(item) }
    Column(modifier.padding(horizontal = 28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (queue) "UP NEXT" else "NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = Mint)
        Spacer(Modifier.height(20.dp))
        if (queue) {
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                itemsIndexed(player.queue) { index, track ->
                    Row(Modifier.fillMaxWidth().clickable { model.playback.select(index) }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (index == player.queueIndex) "▶" else "${index + 1}", color = Mint, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            Artwork(item, model, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(item.subtitle, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LikeButton(state.likes[item.id], item.id in state.pendingLikes, item.id in state.failedLikeStatus,
                    toggle = { model.toggleLike(item) }, retry = { model.checkLike(item) })
            }
            Slider(value = scrub ?: player.positionMs.toFloat().coerceIn(0f, player.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { model.playback.seekTo(it.toLong()) }; scrub = null },
                valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(), enabled = player.durationMs > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time((scrub?.toLong() ?: player.positionMs)), style = MaterialTheme.typography.bodySmall)
                Text(time(player.durationMs), style = MaterialTheme.typography.bodySmall)
            }
        }
        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = player.shuffle, onCheckedChange = { model.playback.toggleShuffle() }) { Icon(Icons.Default.Shuffle, "Shuffle") }
            IconButton(onClick = model.playback::previous) { Icon(Icons.Default.SkipPrevious, "Previous song") }
            FilledIconButton(onClick = model.playback::toggle, modifier = Modifier.size(64.dp)) {
                if (player.isBuffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (player.isPlaying) "Pause" else "Play", Modifier.size(36.dp))
            }
            IconButton(onClick = model.playback::next) { Icon(Icons.Default.SkipNext, "Next song") }
            IconToggleButton(checked = player.repeatMode != Player.REPEAT_MODE_OFF, onCheckedChange = { model.playback.cycleRepeat() }) {
                Icon(if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat: ${when (player.repeatMode) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }}")
            }
        }
        TextButton(onClick = { queue = !queue }) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text(if (queue) "Back to song" else "Up next · ${player.queue.size} songs")
        }
        TextButton(onClick = model::stopPlayback) { Text("Close player") }
        Spacer(Modifier.height(24.dp))
    }
}

private fun time(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
