# Player layout: approved native revision

`docs/reference/index.html` now includes the approved native refinements below.
This note records their layout and ownership constraints alongside that
interactive visual reference.

- Actual album name sits above the artwork and opens a known album through
  the existing navigation callback. The redundant full-player More menu is removed.
- Song title and artist remain below the lyric area, immediately before seek.
  The artist text no longer reserves its own 48dp-high layout gap. Artist
  navigation remains available through the compact identity block.
- Timed inline lyrics use the same supporting text style as album and artist:
  **13.2sp / 17.82sp line height, normal weight**, with native font scaling.
  The decorative lyric icon is removed and horizontal text padding is 8dp
  rather than 26dp.
  The viewport still has a fixed height for a given layout, independent of the
  current line's length. Full text wraps and scrolls vertically without
  ellipsis, shortening, or per-line font shrinking.
- There is no visible “Scroll lyrics” hint, arrow row, or reserved hint space.
  Tap opens the existing full lyrics sheet; vertical dragging reads the text,
  never invokes the player-dismiss gesture. Plain lyrics and timed silent gaps
  retain a direct “View lyrics” entry; pending/error states retain access to the
  sheet and its retry action, without restoring the removed menu. Loading-only
  states do not add a flashing placeholder. Accessibility scroll semantics and
  keyboard arrows/Page Up/Page Down/Home/End are available.
- Elapsed and total time labels are centered in the first and fifth equal
  transport columns, directly above Shuffle and Repeat. The seek target remains
  full width.

## Height budget

The body measures the top album header and all lower controls in the same
layout pass. Safe insets and the existing 16dp top / 18dp bottom padding are
outside that body. Inside it, the header-to-art gap is 8dp.

Artwork is capped at 330dp (164dp for large text) and at available width. It
can shrink toward 164dp to protect the controls and lyric viewport. The lyric
viewport uses the residual gap, retaining at least one natively scaled line,
24dp vertical text padding and an 8dp gap before identity.
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
