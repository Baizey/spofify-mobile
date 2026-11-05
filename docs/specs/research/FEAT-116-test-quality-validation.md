# FEAT-116 — First-slice verification and removal ledger

## Scope and source proof

Landed implementation base: `5b2422f645bc461af3e8419cd6af3399b33fc940`.
Only the three analysis documents were extracted from documentation candidate
`35e1479b9f5e7e45177c66bd4427d8a079af046e`; no unfinished implementation was imported.
Dedicated branch/worktree: `test/test-suite-quality-prune` /
`/var/home/lsm/repos/spofify-mobile-test-quality-prune`.
The source index remains a historical, base-labelled navigation appendix, not a current test result.

Both READY premises were rechecked against actual landed source before pruning.
`PlaylistOccurrenceActions` declares but never reads `contextualLabel`; neither old
component test supplies transport state. Both call precisely the same helper, with
identical fixture, callbacks, composition, inputs and assertions except that ignored
string. Production renders literal Queue High/Low. No production signature was changed.

| Removed execution/assertion | Kept oracle and public guarantee |
| --- | --- |
| Second `PlaylistOccurrenceActionsTest.checkActions` invocation; old idle/active method names and the test-only label parameter | `moreKeepsQueueLowAndHighDistinctReachableAndWithoutMusicalWork` calls the same single unchanged helper body, passing today's `"Queue High"`. Keeps all four action-presence counts; terminal twelfth-credit scroll reachability; Low/High visible, enabled and at least 48dp; no composition callback; exact callback sequence `[enqueue]`, then `[enqueue, contextual]`; real login/session; authenticated POST like-status Cookie/CSRF; exact login/status paths and methods; exactly two requests. No musical endpoint or duplicate callback is allowed. The old names supplied no idle/active evidence and incorrectly suggested unconditional nonstarting behavior. |
| Typography heading size and leading inequalities (eight adjacent comparisons of each dimension) | `compactScaleHasIntentionalSizesAndComfortableLineHeights` retains the complete exact role map: heading sizes `57 > 45 > 36 > 30 > 26 > 22 > 20 > 14 > 13` and leading `64 > 52 > 44 > 38 > 34 > 30 > 26 > 22 > 18`. Thus each removed inequality is strictly implied by retained exact assertions. |
| Typography body size/leading inequalities (two of each dimension) | The same exact map asserts body sizes `14 > 13 > 12` and leading `22 > 20 > 16`, also retaining each role's breathing-room assertion. No unique hierarchy assertion is lost. `assertTrue` remains used; no import was removed. |

Unchanged protection: all six `PlaylistMoreHostTest` manual/derived/collection/stale-binding
cases, including exact duplicate entry and signed occurrence forwarding for all four
intents; `MusicActionsContentTest.fourPriorityActionsAreIndependentOfSampledTransportState`;
all other Typography family/style/scaled-sp/face/resource tests and font-license/large-font
host tests. All playback authority/negative paths, decoder/planner corpus, mocks,
snapshots, fixtures, production, Gradle/category/CI configuration and pins stay unchanged.
The audit's other recommendations remain deferred, not deletion authorization.
No new compensating tests or fabricated RED/mutation run were needed for this deletion-only slice.

## Checks and evidence boundary

- Baseline `ccff844343a27b7b41c3e3232954e59a3bbc013c` (only extracted docs over landed base):
  focused four classes passed, 18 tests, zero failures/errors/skips. This verifies
  retained source/fixtures before pruning rather than treating the audit as execution.
- Focused command: `:app:testDebugUnitTest --tests dev.spofify.mobile.feature.playlists.PlaylistOccurrenceActionsTest --tests dev.spofify.mobile.ui.theme.TypographyTest --tests dev.spofify.mobile.feature.playlists.PlaylistMoreHostTest --tests dev.spofify.mobile.ui.MusicActionsContentTest --offline --no-daemon --console=plain`.
  Baseline explicitly used ordinary category selection (`-Pspofify.realBrokerIntegration=false`), not protocol acceptance.
- Post-pruning source candidate `d094369ac70de6325d53bf271108bab75a4fa264`: the same four focused classes passed, 16 tests, zero failures/errors/skips (one component execution and one implied hierarchy execution removed). Run `e73831df-b141-44ca-9c98-1d1dd0a2f001`, 69 seconds.
- Existing full gate on that source candidate: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain -Pspofify.realBrokerIntegration=true`. All five tasks passed in 851 seconds; fresh XML reports contain core 163 + app 912 = 1075 executions, zero failures/errors/skips. These counts describe this run, not a quality target. Run `c8833764-76c4-4633-b330-36d4988d9040`.
- Full gate used the documented API37.0 compile platform, the verified immutable Go executable SHA-256 `425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4`, installed Playwright/Chromium and isolated owned loopback/runtime paths. No machine-specific default test behavior, mock fallback or skip was added. Default-only unit success is not cited as real-broker acceptance.
- Lint passed with 15 warnings and one hint, zero errors: EmptySuperCall, two ApplySharedPref, InlinedApi, OldTargetApi, ConfigurationScreenWidthHeight, two ModifierParameter, InsecureBaseConfiguration, six UseKtx; AutoboxingStateCreation hint. This test-only slice does not fix or suppress those findings.
- Built APK SHA-256: app `bae2ffbf7d35c7ca08dcb545a00e090fe31c78631571ee58219e0a0d6d0e7783`; instrumentation `a4457aa41e44399347e1475ec0241ad949f0b4dc5d2476728a61346aa1d283a2`. Built artifacts are not installed-Android evidence.
- `python3 -B scripts/test_specs.py`: 26 regression tests passed. `git diff --check`: passed. Final evidence-only documentation does not change the tested source/configuration; the full reviewed candidate/base are recorded externally to avoid a self-referential SHA.

Ignored, owned execution evidence is under root `.tooling/feature-delivery/test-suite-quality-prune/`.
The small adapter imports the existing immutable BUILD-only supervisor unchanged
(SHA-256 `8a23d8f30c5d6fc92011934fd9f754d3c8218a36b380a3725366346412e6704e`),
relocates only this lane's evidence and supplies the four focused task filters.
It never acquires or uses custom Git/MAIN guards/locks. Full gate keeps the existing
mandatory real-broker category and immutable executable pin.

Preflight checked `/tmp`, repository/cache/output filesystem and visible quota.
Baseline saw about 27GB available in `/tmp`, 3.17TB in the repository view and
1,000,777 free `/tmp` inodes. Repository inode totals are hidden (zero reported);
quota lookup returned socket mount errors: **actual quota headroom remains unknown**.
The supervisor bounds combined scratch to 2GiB, outputs to 2GiB, logs to 16MiB
and retained run evidence to 128MiB, with one test fork/two Gradle workers.
Its premain verifier checks the actual forked JVM `java.io.tmpdir`, not just TMPDIR.
Baseline run `df424cbe-062e-496d-916e-b4cdc7ced27d` passed with fresh ECHILD proof,
owner-token-checked BUILD release, scratch cleanup (60,811,403 bytes) and no collector errors.
Post-pruning focused/full runs likewise proved fresh ECHILD and matching-owner BUILD release with no collector errors. They reclaimed 60,811,403 / 103,613,050 bytes of scratch respectively, plus the full gate's owned `/dev/shm` runtime after fixture shutdown. Compact result/XML/lint/APK hashes remain in each owned run's evidence. Final output/report preservation, exact-SHA review, normal reviewed squash, postchecks and nonforced worktree cleanup remain lead-owned after delivery; records live outside the frozen candidate.

**UI validation blocked / not complete.** This test-only slice changes no production
presentation and launches no emulator, installs no APK and supplies no new Android
render/audio/background evidence. The documented preboot-emulator assessment remains
unresolved; this environment exposes no `/dev/kvm`, but that alone is not proof that
all software AVDs fail. No physical/personal device, live account, backend or download
was used. Source/Robolectric/integration results do not waive native acceptance.
