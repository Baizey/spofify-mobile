# FEAT-108 — Profile as a normal full-screen destination

## Tracking

- ID: FEAT-108
- Status: blocked
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: Profile destination, nested offline Credits and session/navigation lifetime
- Next action: Source delivered in c059c781e8acd8f90669f4fa92235fa0df83ee86; its initial main postcheck FAILED, then scoped correction 4364bb9f87b75b77ef2bebbe8d5afdc3799cbc7c passed. Native rendered/audio/background acceptance remains BLOCKED / NOT COMPLETE. Validate current Profile/Credits and four-tab Back/session continuity; historical Profile device entry is superseded by FEAT-110 picker placement beside transport and must not be restored. No source reimplementation or original-postcheck pass is claimed.

## Deliverable and authority

Replace the signed-in account popup with a basic **Profile** tab and nested **Credits**. This is one independently deliverable feature; `FEAT-107-streamlined-login.md` is a peer, not a prerequisite. Grounded source base: `9d6be64bb8058de22fc46915c8fae274dfb3dda8` (clean main). This spec is source analysis, not inspected screenshots or native approval.

The latest user request supersedes the three-destination-only rule specifically to permit **Home / Search / Playlists / Profile**. The PROFILE lead must narrowly update both current navigation statements in root `AGENTS.md`, navigation guidance in `docs/mobile-ux.md`, and affected README/`docs/feature-parity.md` claims. Preserve unrelated product/safety policies; do not rewrite global guidance.

## Product acceptance

- Profile is the fourth bottom-navigation destination, occupying the normal destination body in the existing app shell. It is **not** a bigger AlertDialog, sheet, standalone media overlay, or a second Activity/player. Keep the persistent navigation and flat mini-player; do not leave an empty/decorative app bar or repeat a huge generic Profile heading.
- Replace the `Your connection` dialog/account entry with this discoverable destination. Show meaningful authenticated username and current **credential-free canonical server origin**, with essential long/Unicode values wrapping and reachable in a scrollable layout. No password, cookie, CSRF token, account/session/debug IDs or private paths appear.
- Offer **Sign out** and **Credits**, with an existing **Playback devices** entry if useful (reuse `PlaybackDevicesSheet` and its truthful capability state). Preserve existing picker discoverability; no picker redesign, new device protocol, or duplicate inventory of controls.
- No Use/Forget saved-login controls, remember toggle, or saved-credential explanatory prose on Profile. Do not change automatic-saving/auth behavior here. Retain existing logout semantics: invalidate session/output authority, stop locally owned audio and fence stale work; retain the encrypted latest successful login. Remote-revocation failure remains a truthful concise notice, not a failure to sign out locally.
- Credits is a nested destination; a modest JetBrains Mono attribution can open the existing readable local license dialog or a full readable viewer. **No prominent standalone Font license action on Profile root.** Back dismisses any dialog/IME/menu/sheet first, then returns Credits to Profile; root Profile uses normal Android root Back behavior, not a synthetic jump to Home. No duplicate in-app Back arrows/buttons or competing double-pop handlers.
- Switching tabs, opening/dismissing Credits/license/devices, ordinary recomposition, rotation and returning from details preserve the other destinations' state and selected-output queue/current occurrence/position/audio. No prepare, stop, seek, autoplay, authority recreation or transport command from presentation navigation. Route retirement still invalidates uncommitted held row/preparation work, including A→B→A; it must not cancel already playing transport.
- Preserve the landed Search distinction: ordinary state restoration/Back retains query/results/paging/scroll, while an **explicit Search nav activation/reselect** clears to its empty root and delivers its one-shot IME focus intent. Leaving for Profile cancels pending Search activation; no delayed IME over Credits/Profile. Active Search editing still hides navigation/unnecessary chrome.
- Use existing `AppTypography`, colors and compact hierarchy. Check 48dp named targets, contrast, single-consumed safe/gesture insets, 320dp width and font scale 2, long usernames/origins and Unicode fallback. All four labels/actions and last content remain reachable without overlap/clipping.

## Attribution contract

OFL condition 2 requires each distributed font copy to contain its copyright and full license in an easily viewable form; it does **not** require login-page placement. Product placement is Profile → Credits → local full notice. Preserve font files, central typography/fallback and `app/src/main/assets/licenses/jetbrains-mono/OFL.txt` **byte-identically** (current OFL: 4,399 bytes, SHA-256 `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`). No download, external viewer requirement or new licensing architecture. Offline viewing must work with a valid signed-in session even when the server is unavailable. Login's pre-auth link is removed by the LOGIN peer, not this feature; no legal requirement to retain that link.

## Touchpoints and independence

- `app/src/main/java/dev/spofify/mobile/MainActivity.kt`: account popup/state/action hunk, nav item list, destination dispatch, Credits/license/Back ownership. Do **not** change `SignIn`'s current signature/call contract or auth model as part of this feature.
- `app/src/main/java/dev/spofify/mobile/LibraryViewModel.kt`: `Page`/`Tab`, scoped tab stacks/load/Back handling and cancellation of one-shot Search activation. Keep auth/persistence methods and peer-used APIs compatible. A small dedicated Profile composable is appropriate, not a navigation-framework rewrite.
- Existing `ui/ListeningChrome.kt` (including `CompactNavigation`), font dialog and device sheet are reuse/verification surfaces; alter shell geometry only if required for four accessible tabs. No player/service/intent-model changes.
- Shared MainActivity/ViewModel/docs/native-journey files require **hunk coordination**, not a combined branch or prerequisite. Profile owns signed-in account → destination/credits and Profile's saved-control removal; LOGIN owns sign-in wiring, secure automatic saving and URL policy. Preserve unused compatibility APIs while main/peer callers need them. Update attribution-location docs truthfully for whichever peer has already landed; do not advertise unfinished UI.
- No dependency on GoFEAT023, the mobile-priority adapter/spec, preserved priority-queue successor work, recovery lanes, or further backend APIs. No Go writes, preferences inventory, account editing/password management, theme/font changes, recommendations, downloads or permissions.

## Feature-owned evidence and delivery

1. Add unit/host navigation coverage around `LibraryViewModelTest`, `SearchNavigationHostTest`/Search activation tests and `RootCopyHostTest` (or focused Profile tests): four destinations; Profile/Credits stack and root Back; no popup/saved controls; username/origin rendering; preserved per-tab state; pending activation/held row callbacks retired without transport. Cover logout/expiry/account replacement so Credits/private identity cannot survive into another session. Reuse session/playback regressions and device picker tests rather than redesigning them.
2. Preserve `ui/theme/FontLicenseTest`/`TypographyTest` full-asset, font/fallback and package checks; add host tests proving offline Profile → Credits → complete notice and native dismissal ownership. Adjust only relevant `NativeUiSmokeTest` account/credits/navigation hunks; do not remove unrelated music/IME journeys or depend on Login's new controls.
3. Run the full API37.0 **offline automated gate** in [testing.md](../testing.md) from the feature's own worktree. Actual isolated-AVD after-renders are a separate gate: capture/inspect Profile, Credits/full license, long values, 320dp/font2, Back/tab/IME transitions, loading/error contexts and ongoing selected-output playback continuity. Smoke Sign-in/Home/Search/Playlists/Now Playing; compare before/after where available and answer [rendered-review questions](../mobile-ux.md#rendered-review-questions--answer-with-evidence-not-source-assertions). Implementer and independent reviewer inspect real images; source/Robolectric are not native approval.
4. Known current evidence: emulator 37.2.13 + API37 x86_64 software image failed before boot, with no usable KVM reported. Native acceptance is **blocked / not complete**, not proof every isolated alternative fails. Do not repeat identical diagnostics for a checkbox, target physical/personal devices, use live accounts/PC/backend, or claim a native pass without actual evidence.
5. Read/follow root policies and [worktree-review](../worktree-review.md), [ui-validation](../ui-validation.md), [storage-hygiene](../storage-hygiene.md) fully. Each shared BUILD/MAIN/NATIVE resource needs atomic `mkdir` owner-token guard **plus** persistent exclusive flock, finite/fair acquisition, no stealing/unlinking locks. BUILD uses exclusive absolute root `.tooling/gradle` cache with own outputs; no cache repair/purge. Release BUILD before MAIN/native; root postchecks acquire MAIN→BUILD, native runs standalone NATIVE with exact APKs/`--skip-build`. Preflight quota/owned bounded scratch and actual test-JVM temp; account/clean only stopped own outputs and preserve needed evidence.
6. Independently review the exact candidate SHA against its actual validated main base; catch up in own topic if main moved, recheck/re-review, then individually guarded squash/postchecks per workflow. A separate scoped resolver reads both specs for real conflicts. Source landing with explicitly blocked native evidence is not feature completion. Parent combined smoke supplements individual gates. Preserve this analyzer docs lane for extraction/takeover; implementer cleans only fully accounted integrated own lanes without force.

**Open product decisions:** none required. Main risks are four-tab narrow/font2 geometry, nested Back/session ownership and accidental Search/playback-state regression; prove them rather than expanding scope.
