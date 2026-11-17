# Artist discography

The native artist page separates the artist’s own **Albums** and **Singles**, with
**Appears on** collapsed at the bottom. Empty groups are omitted. Opening the
secondary section is a presentation action, not a playback or download command.
Its expanded state belongs to the displayed artist and survives configuration
recreation without opening a different artist’s appearances.

## Metadata and grouping

`LibraryClient.artistAlbums` continues to request published, in-library releases
with `scope=all` and `releaseGroup=all`. The shared `MusicItem` projection retains
the backend’s `albumType` and artist-relative `relation` fields. Search and offline
contract projections retain these fields too; offline storage is still not enabled.

The primary groups follow the backend’s existing release-group definitions:

- **Albums:** `relation=release` and `albumType=album`.
- **Singles:** `relation=release` and `albumType=single` (the provider category also
  used for EPs).
- **Appears on:** guest appearances, compilations and other release types. A guest
  single or album stays here, not in the artist’s own discography.

Missing or unfamiliar metadata remains in the secondary group rather than being
claimed as an own album or single. Unknown string values are preserved, not
replaced by guessed facts. Ownership is never inferred from display names or from
the position of an artist credit. The expanded secondary section explains its
coverage. There are no extra primary compilation or EP categories.

## Paging and interaction

Grouping preserves server order within each group and never removes release
identities. Groups describe the loaded catalog prefix; they do not claim complete
counts. The existing revision-fenced artist pager remains authoritative. Its load
state is before the secondary section, so discovering subsequent own releases
never depends on expanding appearances. Hidden appearances’ metadata may be read
while paging; this does not fetch audio. Errors retain the existing Retry behavior.

Release cards still open the same album details, and album/artist playback remains
server-selected. The grid keeps two columns, switching to one above a font scale
of 1.4. The secondary toggle has a full-width, at-least-48dp target and an explicit
expanded/collapsed accessibility state. Artist rankings remain unavailable rather
than being invented from release pages.
