# Player layout: approved native revision

`docs/reference/index.html` remains frozen. This note records the user's later
player-specific changes, rather than changing that reference.

- Actual album name and the existing song More menu sit above the artwork.
  A known album can be opened through the existing navigation callback.
- Song title and artist remain below the lyric area, immediately before seek.
  The artist text no longer reserves its own 48dp-high layout gap. Existing
  navigation and song menu actions are unchanged.
- Timed inline lyrics keep **33sp / 40.26sp line height**, with native font
  scaling. Their viewport has a fixed height for a given layout, independent
  of the current line's length. Full text wraps and scrolls vertically; it is
  not ellipsized, shortened, or reduced in font size.
- A visible up/down “Scroll lyrics” cue appears only when the measured text
  overflows. Space for it is reserved to avoid moving the text after measure.
  Tap opens the existing full lyrics sheet; vertical dragging reads the text,
  never invokes the player-dismiss gesture. Accessibility scroll semantics and
  keyboard arrows/Page Up/Page Down/Home/End are available.

## Height budget

The body measures the top album/menu row and all lower controls in the same
layout pass. Safe insets and the existing 16dp top / 18dp bottom padding are
outside that body. Inside it, the header-to-art gap is 8dp.

Artwork is capped at 330dp (164dp for large text) and at available width. It
can shrink toward 164dp to protect the controls and lyric viewport. The lyric
viewport uses the residual gap, retaining at least one natively scaled line,
24dp vertical text padding, the overflow cue, and an 8dp gap before identity.
Its base minimum is 96dp before that final gap. Timed clearing lines retain
this same space, without claiming an old line is still current.

When that budget cannot fit, the body retains the readable viewport and
artwork floor and scrolls as a whole. The bounded lyric scroller nests inside
it, so controls and terminal content remain reachable on short/narrow screens
or with large text. Non-lyric breathing space retains its 16dp minimum.

Inline scroll resets by resource/occurrence binding, recording/source version,
and timed line index/start/text—not by playback ticks or seeking within the
same line. Reset is immediate, with no added motion or animation. The existing
actual-duration gates, account/content fences, seek behavior and full lyrics
sheet are unchanged.
