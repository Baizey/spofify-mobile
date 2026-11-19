package dev.spofify.mobile.designsystem

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Geometry from docs/reference/index.html, shared by all native music surfaces. */
enum class SpofifyGlyph(private val path: String, private val filled: Boolean = false) {
    History("M3 4v6h6M3 10a9 9 0 1 1 1 8M12 7v5l3 2"),
    Search("M20 20l-5-5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0"),
    Playlists("M4 5h16M4 11h16M4 17h10"),
    Settings("M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1ZM16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0"),
    Play("m9 5 11 7-11 7Z"),
    Pause("M8 5v14M16 5v14"),
    Devices("M3 4h18v13H3ZM9 21h6m-3-4v4"),
    More("M12 5h.01M12 12h.01M12 19h.01"),
    Note("M10 18V5l9-2v13M10 18a3 3 0 1 1-6-1 3 3 0 0 1 6 1m9-2a3 3 0 1 1-6-1 3 3 0 0 1 6 1"),
    Album("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M14 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0"),
    Artist("M17 7a5 5 0 1 1-10 0 5 5 0 0 1 10 0M3 22a9 9 0 0 1 18 0"),
    Down("m6 9 6 6 6-6"),
    Downloaded("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v9m-4-4 4 4 4-4"),
    Up("m6 15 6-6 6 6"),
    Next("m5 5 11 7-11 7ZM19 5v14"),
    Previous("m19 5-11 7 11 7ZM5 5v14"),
    Shuffle("M3 6h3c5 0 7 12 12 12h3m-3-3 3 3-3 3M3 18h3c2 0 4-2 6-6s4-6 6-6h3m-3-3 3 3-3 3"),
    Repeat("M4 8V5h14l3 3-3 3M20 16v3H6l-3-3 3-3"),
    RepeatOne("M4 8V5h14l3 3-3 3M20 16v3H6l-3-3 3-3M11 10l2-1v6m-2 0h4"),
    Check("m5 12 4 4 10-10"),
    Plus("M12 4v16M4 12h16"),
    Copy("M8 3h13v13H8ZM3 8v13h13"),
    Edit("m4 16 12-12 4 4L8 20H4ZM14 6l4 4"),
    Trash("M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15"),
    Close("m6 6 12 12M6 18 18 6"),
    Lyrics("M4 5h16M4 11h10M4 17h7M17 15l4 3-4 3Z"),
    Flame("M12 2c1 5 6 5 6 11a6 6 0 0 1-12 0c0-2 1-4 3-6 0 3 1 3 2 4 2-3 1-5 1-9Z"),
    Queue("M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zm14-10v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z", true);

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

@Composable
fun SpofifyIcon(
    glyph: SpofifyGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(glyph.vector, contentDescription, modifier, tint)
}
