# FEAT-102 — Compact bottom chrome, explicit Search activation and quieter typography/copy

## Tracking
- ID: FEAT-102
- Status: todo
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: MainActivity; ListeningChrome; SearchRoute/CommittedSearchField; CatalogState; LibraryViewModel navigation; Typography; Home and Playlists copy; scoped UI/state tests
- Next action: assign lead B; inspect/build isolated tokens/components now, wait for FEAT-101 shared-file source handoff before editing host/search/playlist wiring

## Scope and source dependency

Read `_20261104-mobile-polish-handoff.md` and its required docs. This cohesive UI lane owns bottom navigation/search presentation, Search-tab activation, central type scale and redundant root empty/help copy. Keeping these together avoids independent chrome/focus changes racing over the Activity and Search field.

FEAT-101 is the first shared-file writer. B may inspect and work in new components/`Typography.kt`/`ListeningChrome.kt` independently, but takes `MainActivity.kt`, navigation portions of `LibraryViewModel.kt`, `SearchRoute.kt`, `LibraryHome.kt`, `PlaylistsRoute.kt` and overlapping tests only after A freezes/commits and explicitly hands off its exact source candidate. This is a source barrier, not a requirement for A to be declared natively done. B owns these files afterward; do not undo A's new queue/actions while resolving conflicts. C owns service/media controls; request any extra host hook rather than allowing parallel Activity writers.

## Acceptance: compact persistent chrome

- Preserve **Home / Search / Playlists**, readable labels/selected state and native focus/selection semantics. Reduce the navigation's visual content height from Material's default approximately 80dp to a deliberate compact target around **56dp excluding system bottom inset** at normal font scale. Use a component/custom row appropriate to real minimum-size behavior; wrapping a still-80dp Material component in a clipping box is not a fix. Each destination retains at least 48dp actual non-overlapping target and enough width for labels. Let large fonts grow/adapt instead of clipping.
- Navigation surface matches the app background, without tall pale/tonal slabs, unnecessary elevation, outer gutters or extra blank bars. Do not remove Android gesture/three-button safe space to meet the height target. Count insets exactly once, including landscape cutouts and IME; retain the full-width flat mini-player and last-result reachability.
- Bottom Search input stays in the lower thumb zone above persistent playback/navigation when not editing, and directly reachable above IME while editing. Aim for **48dp field minimum height** at default font scale, approximately 56dp total dock with modest padding, instead of default 56dp field plus broad dock/outline/elevation. This is a visual budget, not permission to clip glyphs or reduce clear/search hit targets below 48dp. Large fonts may increase it.
- Field/dock resting background is the exact app background, no contrasting pale-green fill, raised tonal Surface, oversized pill or heavy outline. Prefer square/coherent repeated-surface geometry, subtle focus indication and clear high-contrast text/cursor/placeholder. Focus must remain perceptible; do not set every border color transparent and remove all focus cues. Clear control remains discoverable and labeled; no added filter/type/count rows.
- During active editing, remove bottom navigation and unnecessary top actions **and reserved space**; preserve mini-player, query/results and lower input. Ending editing restores chrome without clearing a normal query or jumping content. Insets and hiding must not make focus oscillate or recreate the field. Preserve IME composition/debounce and submit behavior.

## Acceptance: bottom Search tap is a fresh search intent

Every deliberate **Search bottom-nav tap**, including reselect, must atomically:
1. Navigate to the Search **root** even if the host's saved Search tab or CatalogStateHolder is displaying a nested detail.
2. Clear the accepted query **and local draft/composition**, results/paging and search-root scroll; cancel/fence previous in-flight/debounced reads. Empty query issues zero match-all catalog calls. Stale responses must not repopulate cleared results.
3. Once the real field is composed, attached and lifecycle-active, focus it and request the software keyboard. A monotonically changing transient activation identity/event is appropriate; a sticky boolean or `LaunchedEffect(Unit)` is not sufficient for reselect. A route switch can destroy the nav immediately as editing begins; that must not cancel its intended focus delivery.

This explicit tap reset is an exception to ordinary preservation, not a replacement for `switchTab` everywhere. Back from details, rotation/recomposition, programmatic switching, Home/Playlists taps, returning to Search by existing navigation, and collapsing the player must **not** clear/refocus Search or reset playback. Consume activation once for the current client/session/lifecycle; do not replay keyboard requests on rotation, restoration, account change, ordinary query publication or hidden routes. Reset must override a composing `TextFieldValue` even though normal external query reconciliation deliberately waits for composition to finish. Handle same empty query/reselect and preexisting inner stacks, not only changed strings.

Back dismisses IME/editing first, then menus/sheets, then details/navigation; no duplicate Back button and no second pop behind keyboard. Keyboard absence with a hardware keyboard is not a test failure if field genuinely receives focus; native software-keyboard evidence is still required where supported. Opening Now Playing clears underlying field focus/IME without clearing query; collapse does not refocus it unless a new explicit Search tap occurs.

## Acceptance: modest type scale and minimal copy

- Change shared `AppTypography` deliberately: reduce major headings/body roles modestly (typically 1–2sp for common body/title roles), maintaining hierarchy, sensible matching line heights and central tokens rather than scattered screen-specific font sizes. Suggested starting range: common primary row/body text 13–14sp, secondary 12sp; do not shrink already-small labels/captions below their current 11–12sp range just to reduce every number. Final values need rendered review, not blind percentage scaling.
- Keep bundled JetBrains Mono regular/medium/semibold/bold, Android Unicode/emoji fallback, scaled `sp`, readable contrast, essential wrapping and 48dp actions. Never override density/fontScale, disable minimum targets, crop line heights or swap family to fit. Font-license notice/assets remain unchanged and accessible offline.
- Update `TypographyTest.materialDefaultsAreUnchangedApartFromFontFamily`: its old equality contract is intentionally superseded, not a reason to avoid central scale changes. Assert intended sizes/line-height hierarchy/family/weights and no regression to unscaled text. Check all screens affected by shared tokens, including Sign-in/forms/dialogs/player, not only Search.
- Empty Search needs at most its field placeholder and accessibility label; remove the paragraph `Find an artist, album or song ... Start typing ...`. A short no-results status for a **submitted nonblank** query remains useful. Do not fabricate an empty result for loading/error.
- Remove Home `Create a playlist to collect your music.` and recent-listening tutorial prose; omit empty decorative shelves or show at most a short honest state, retaining the Liked songs and genuine playlist navigation/create path. Remove Playlists `Make a playlist ... or choose live rules.` and redundant generic destination headline when selection already supplies context. Keep concise **Create playlist** and recovery actions discoverable; no duplicate instructions surrounding them. Reduce equally redundant Liked Songs root empty prose in Activity to a short state if needed.
- Audit analogous gratuitous help on affected root surfaces, not every warning in the application. Preserve meaningful entity titles, useful counts, unavailable status, partial-error/retry, saved-data confirmation, uncertain-write/revision/derived limits, credential/HTTP warnings and legal/accessibility text. FEAT-101 owns removing the obsolete derived dialog instruction to use a now-removed row Play button. Avoid an unrelated playlist-editor redesign or weakening mutation safeguards.

## Ownership

Primary production files:
- `app/src/main/java/dev/spofify/mobile/MainActivity.kt` (navigation/activation, root copy; preserve A's action wiring);
- `ui/ListeningChrome.kt` and optional new compact navigation component;
- `ui/theme/Typography.kt`;
- `feature/library/SearchRoute.kt`, `CatalogState.kt`, `LibraryHome.kt`;
- navigation/search state in `LibraryViewModel.kt`;
- root presentation/copy only in `feature/playlists/PlaylistsRoute.kt`.

Own scoped SearchField/SearchEditing/SearchPresentation/CatalogState/ListeningChrome/Typography/Home/playlist-root/navigation tests and existing `NativeUiSmokeTest.kt` after A's handoff. Add a dedicated host activation test if existing coverage cannot drive the actual tab path. No playback service/queue algorithm, MediaSession wrapper or player sheet changes. Global typography may alter player rendering: report/fix only necessary type-layout regressions via coordinated scoped ownership, not a sheet replacement.

C may request standard Android music-volume stream routing in `MainActivity.onCreate`; B owns the small hook (`volumeControlStream = AudioManager.STREAM_MUSIC` if needed), C owns media behavior/testing. This is not a Compose key interceptor or duplicate hardware-event handler.

## Test/evidence checklist

- State/unit: Search activation from Home, Playlists, host detail, inner Search detail and already-selected root; empty/nonempty/composing query; repeated taps; reset of results/paging/scroll; held old response and debounce cancellation; account/lifecycle fence; zero empty-query catalog requests. Verify normal Back/tab/programmatic return preserves state and does not generate activation.
- Compose host tests drive **actual nav tap and reselect**, assert focused empty field and root route, exact activation consumption, disappearing/restored chrome, no reserved toolbar/nav bounds, and no playback commands. Include recomposition/rotation and player expansion/collapse; local draft cannot restore an obsolete query. Mock keyboard show calls are development evidence, not proof the IME actually opened.
- Geometry: normal/narrow/landscape/default/font2, 48dp destinations/clear/More, intended 56dp nav content and smaller dock excluding genuine insets, labels/glyphs not clipped, transparent/equal-background field/dock, visible focused state, full-width mini-player and terminal result above chrome/keyboard. Assert measured component sizes rather than modifier presence. Typography hierarchy and exact bundled family/licenses stay tested.
- Copy/error: empty/loading/nonempty/error/partial-failure Search; empty/failed/populated Home shelves; Playlists root with create/retry; Liked Songs empty. Removed paragraphs are absent, necessary warnings/recovery remain, error is not empty.
- Run common offline full gate with BUILD lease and exact-SHA review. Under updated native guidance assess feasibility afresh, then capture and **inspect** current-APK before/after default+narrow/font2 Search closed/open IME and every affected root screen; exercise Search-nav tap/reselect/Back/clear/submit, scroll and player continuity. Record total top/bottom chrome budget with and without IME and complete row counts at identical viewport/font scale. Inspect Sign-in, dialogs, Playlists and Now Playing for global type regressions. Do not fabricate missing before screenshots or claim the available cropped row image shows chrome.

## Security/non-goals/completion

No new backend request/permission, match-all Search, acquisition/admin APIs, credentials/query persistence changes, remote font loading, keyboard-input logging, new animations/filter bars or broad player redesign. Queries and focus events remain current-account/transient. Retain exact-origin auth/storage/privacy and native Back/IME security conventions.

Use worktree/source handoff, storage policy, independent exact-SHA review and clean-main integration under [worktree-review.md](../worktree-review.md), with normal Git locking and brief root-write coordination. The former user-dirty-main blocker was resolved by cleanup `bb3613c`; recheck current state and stop on new unrelated dirt. Consume separate docs cleanup; native feasibility is fresh, not automatically blocked by a stale stop or automatically passed by source tests. Final handoff clearly distinguishes integrated source, actual rendered/keyboard validation and unverified gates.
