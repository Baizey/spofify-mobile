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

private const val REPEAT_PATH = "M19 3l3 3-3 3m3-3H6a3 3 0 0 0-3 3v2m2 10-3-3 3-3m-3 3h16a3 3 0 0 0 3-3v-2"

/**
 * Shared native glyphs. Common geometry comes from spofify-desktop/assets/icons at
 * 551e137bc90c8ae593386beae657f86f9836bfba, with SVG shapes expressed as path contours.
 */
enum class SpofifyGlyph(
    private val path: String,
    private val filled: Boolean = false,
    private val strokeWidth: Float = 1.7f,
) {
    History("M3 12a9 9 0 0 1 18 0a9 9 0 0 1-18 0zM12 6v6l4 2"),
    Search("M3.5 10a6.5 6.5 0 0 1 13 0a6.5 6.5 0 0 1-13 0zM15 15l5.5 5.5"),
    Playlists("M4 5h16M4 11h16M4 17h10"),
    Settings(
        "M9 12a3 3 0 0 1 6 0a3 3 0 0 1-6 0z" +
            "M10 3l4 0 .7 2.5 2 .9 2.3-.7 2 3.4-1.9 1.9v2l1.9 1.9-2 3.4-2.4-.7-1.9.9L14 21h-4l-.7-2.5-2-.9-2.3.7-2-3.4L5 13v-2L3 9.1l2-3.4 2.3.7 2-.9z",
    ),
    Play("M8 5v14l11-7z", filled = true, strokeWidth = 0f),
    Pause("M8 5v14m8-14v14", strokeWidth = 4f),
    Devices(
        "M4 4h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
            "M6 20h7M16.5 9h4a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-9a1.5 1.5 0 0 1 1.5-1.5z",
    ),
    More(
        "M2 12a2 2 0 0 1 4 0a2 2 0 0 1-4 0z" +
            "M10 12a2 2 0 0 1 4 0a2 2 0 0 1-4 0z" +
            "M18 12a2 2 0 0 1 4 0a2 2 0 0 1-4 0z",
        filled = true, strokeWidth = 0f,
    ),
    Note("M8 16V6l11-2v10M8 9l11-2M2 17a3 2.5 0 0 1 6 0a3 2.5 0 0 1-6 0zM13 15a3 2.5 0 0 1 6 0a3 2.5 0 0 1-6 0z"),
    Album("M3 12a9 9 0 0 1 18 0a9 9 0 0 1-18 0zM9.5 12a2.5 2.5 0 0 1 5 0a2.5 2.5 0 0 1-5 0zM6 12a6 6 0 0 1 6-6"),
    Artist("M8.5 8a3.5 3.5 0 0 1 7 0a3.5 3.5 0 0 1-7 0zM4 20c0-4 4-6 8-6s8 2 8 6"),
    Down("m6 9 6 6 6-6"),
    Downloaded("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v9m-4-4 4 4 4-4"),
    DownloadPending("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v5l3 2"),
    DownloadFailed("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 7v6m0 3h.01"),
    Up("m6 15 6-6 6 6"),
    Next("M17 5v14M5 5l10 7-10 7z", filled = true),
    Previous("M7 5v14m12-14L9 12l10 7z", filled = true),
    Shuffle("M3 6h2c5 0 7 12 12 12h4m-4-3 4 3-4 3M3 18h2c2 0 3-2 4-3m6-7c1-1 1-2 3-2h3m-4-3 4 3-4 3"),
    Repeat(REPEAT_PATH),
    RepeatOne(REPEAT_PATH + "m-11-2 2-1v5"),
    Check("m5 12 4 4 10-10"),
    Plus("M12 4v16M4 12h16"),
    Copy("M8 3h13v13H8ZM3 8v13h13"),
    Edit("m4 16 12-12 4 4L8 20H4ZM14 6l4 4"),
    Trash("M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15"),
    Close("m6 6 12 12M18 6 6 18"),
    Lyrics("M9 5.5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0zM5.5 10.5v1a6.5 6.5 0 0 0 13 0v-1M12 18v3.5m-3.5 0h7"),
    Flame("M12 2c1 5 6 5 6 11a6 6 0 0 1-12 0c0-2 1-4 3-6 0 3 1 3 2 4 2-3 1-5 1-9Z"),
    Queue("M3 6h17M3 12h10M3 18h7m6-7v9l6-4.5z");

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (strokeWidth > 0f) SolidColor(Color.Black) else null,
            strokeLineWidth = strokeWidth,
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
