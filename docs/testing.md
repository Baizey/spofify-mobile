# Validation

Read [mobile-ux.md](mobile-ux.md) and [ui-validation.md](ui-validation.md) before changing a screen. Follow [worktree-review.md](worktree-review.md) for dedicated worktrees, exact-SHA review, separate advisory-locked shared-cache builds, standing conditional assigned-scope self-integration in **main**, and evidence-preserving cleanup after scoped/combined validation. Temporary integration branches are optional, not the final destination. **Automated checks and rendered native validation are separate gates.**

## Storage preflight and cleanup

[Storage hygiene](storage-hygiene.md) is part of every build/test/capture run, including delegated work. Before starting, check temporary/output capacity and visible quota, assign bounded owned scratch, and verify the forked test JVM temporary directory (`TMPDIR` alone is not enough). Preserve necessary reports/evidence, then clean verified disposable scratch after all owning processes stop; account for failure/cancellation/crashed descendants. Keep logs and tool output bounded. EDQUOT/ENOSPC stops artifact generation pending investigation and safe cleanup, not blind retries. The historical incident lesson and mandatory handoff checklist are in that document. These obligations do not relax the separate build lease or isolated-AVD safety checks.

## Automated gate

Use the provisioned API 37.0 compile platform via the worktree's local SDK configuration; minimum Android remains API 26 and target SDK remains 36. Assigned leads may acquire the exclusive `spofify-gradle.lock` advisory lease under the absolute Git common directory themselves (see worktree policy), verify no uncoordinated build is active, then run from their own worktree while continuously holding it:

```sh
GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle \
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
    :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain
```

The absolute cache above is shared and its use must be serialized; indexes and build outputs remain worktree-local. Release the build lease after recording results; no overlapping builds. This lease is separate from `spofify-main.lock` and does not authorize main writes. Own-worktree builds hold **BUILD only** and release it before attempting MAIN. Root/main post-integration Gradle acquires **MAIN then BUILD**, holds both to freeze main source throughout compilation and releases BUILD before MAIN. Never acquire build→main or overlap integration with root compilation. CI uses JDK 21; local validation uses JDK 25. APK paths can be under `outputs/` or `intermediates/`, depending on AGP; the native runner finds the actual artifacts.

Core tests use MockWebServer, never the live backend or real accounts. Synthetic in-process/loopback fixtures are allowed; they do not authorize external network diagnosis or live-backend access. They cover exact origins/IP policy, opaque IDs, authentication/CSRF, cookies, redirect refusal, one-shot writes, session-generation races, cancellation, transport bounds, catalog paging/revisions and personal/derived collections.

App JVM/Robolectric tests cover encrypted storage and remembered-login behavior, stale-session fences, search/navigation/state, playlist builders, likes, queue/occurrence selection, playback coordination/progress, bundled typography and the full offline font-license asset/dialog. They are **not** native screenshot or physical-hardware tests.

Re-run the gate for each candidate and use its current XML reports, APKs and lint output. Historical test totals and ignored logs are not validation of the current SHA. Inspect packaged font faces and the complete OFL notice when changing typography/licensing assets; record remaining warnings rather than assuming an old count is current.

## Required native AVD gate

Normal isolated synthetic AVD validation is allowed. After the leased automated build above, reuse those exact APKs (verify their recorded hashes):

```sh
tools/ui/validate-native.sh --skip-build
```

The runner's default build does not supply `--offline`, so do not use it to bypass the exclusive build lease and offline gate. The runner uses a dedicated synthetic AVD, disabled ADB mDNS/wireless discovery and USB transports, an explicitly selected loopback emulator transport, and a fixture server inside the emulator. Its native test also rejects a non-emulator before Activity launch or test writes. Agents must not target real phones, personal AVDs or live accounts. Do not use an unqualified `adb install` or arbitrary-device `connectedAndroidTest`.

**Open and actually inspect screenshots**, exercise the changed flows, fix regressions, and recapture. Passing instrumentation without image review is not visual approval. Record the APK hash/revision, image/API/viewport, font scale, actions, image paths, findings and reviewer. Compare before/after when a baseline exists and answer the [mobile rendered-review checklist](mobile-ux.md#rendered-review-questions--answer-with-evidence-not-source-assertions). An independent reviewer inspects affected screenshots; fix non-nit findings and re-review until clean.

### Native evidence status

Native after-render/audio/background acceptance remains **not complete** in the evidence available to this documentation cleanup. Earlier attempts with emulator 37.2.13.0 and an API 37.0 x86_64 image reportedly segfaulted before app launch under software emulation, including a disposable RAM-backed attempt. The earlier report found no usable `/dev/kvm`; these are historical observations, not verified current environment facts or proof that all AVDs fail.

Historical diagnostic evidence was recorded under `.tooling/ui-testing/runs/20261103T235519Z-2/`; the user-supplied before-only image was preserved at `.tooling/ui-testing/reviews/20261104-user-before.png` in the root checkout. These ignored local artifacts may not exist in every checkout. Neither is current-APK after-validation. This cleanup ran no emulator or toolchain diagnosis and establishes no successful native pass.

The old one-run diagnostic STOP and exhausted-budget instruction are retired. Run normal isolated synthetic AVD validation using [ui-validation.md](ui-validation.md), retaining discovery/physical-device/personal-data/live-backend restrictions. If a run cannot proceed, record the current concrete blocker and report **UI validation blocked / not complete**. Do not infer success from lifting the stop or substitute a real phone, source checks or Robolectric.

## Native acceptance checklist

Use synthetic fixture accounts/library data, not the live backend.

1. Sign-in: valid/invalid origins, HTTPS and permitted trusted-network HTTP, risk warning, remembering, saved-login use/forget, masked password and reachable IME submission. Full JetBrains Mono license opens offline before authentication and dismisses via Android Back.
2. Home/Search: bounded personal shelves, real Liked Songs counts, no fabricated recommendations or empty-query catalog fetch; bottom query reachable with IME; hide bottom navigation and unnecessary/blank toolbar during active editing and restore on edit end. Keep grouped ordering without visible result-type title/count rows, retaining non-verbal/accessibility type cues. Generous progressive expansion and partial retry, without persistent filters, tiny paging controls or empty-group message walls.
3. Navigation: native Back dismisses IME, then menu/sheet, then nested route without double-pop. Tabs and detail returns preserve query/scroll/playback. No duplicate in-app Back arrows.
4. Details/playlists: meaningful entity names, credits, long/Unicode names, missing art/audio, duplicate/unavailable entries, manual editing and derived recipe labels/evaluation/error recovery. Keep useful content during refresh and stale-revision revalidation.
5. Playback: full/capped contexts and late selections; seek, previous/restart, next, shuffle, repeat and occurrence-aware queue operations/Undo. Collapse Now Playing with Back or downward dismissal without changing audio, position or queue. Browsing/enqueue/reconnection never starts audio.
6. Lifecycle/auth: rotation/recreation, leaving/backgrounding the Activity, notification/system media controls, no autoplay after restart; logout/expiry stops buffered output and stale work cannot affect another account/server. History requires actual progress, not preparation/seeking.
7. Device sheet: truthful view-only state without registration, and correctly gated selected-output controls when registered. Exercise exact authority-fenced handoff with synthetic endpoints where applicable; a picker or enabled source is not proof of successful transfer. See [device-playback.md](device-playback.md).
8. Accessibility/layout: narrow viewport, increased font scale, JetBrains Mono wrapping and Android Unicode/emoji fallback, contrast, 48dp targets, TalkBack labels, bottom/gesture/IME insets, last-row reachability and accessible alternatives to music gestures. Check complete visible-row counts, aligned compact art/title plus one inline credit/metadata line, modest artist type and uniform row/action geometry across artist counts, availability and refresh. No stacked artist buttons; preserve rich artist/album actions through details/More. Mini-player is full-width and flat, not a rounded floating card.
9. Storage/privacy: encrypted origin-bound sessions and opted-in credentials; no plaintext logs, backup/device transfer, audio cache or authenticated image disk cache. Every API/art/audio/range request remains on the exact-origin authenticated client; redirects fail safely.

## Hardware-specific limits

Emulators cannot establish every Bluetooth/vendor/codec/power-management behavior. Describe the precise unverified behavior and coordinate an exceptional check **through the user**, if needed; agents do not directly connect to or install on physical phones. Hardware access is not a routine prerequisite for ordinary emulator-validated work. No universal codec support or native runtime handoff approval is claimed.
