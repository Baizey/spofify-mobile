# BUG-001 — Native validation environment unavailable

## Tracking
- ID: BUG-001
- Status: blocked
- Phase: implementation
- Spec readiness: ready
- Owner: environment-coordination
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Discovery depends on: none
- Touchpoints: docs/testing.md current-native-status; docs/ui-validation.md; tools/ui/validate-native.sh; ignored .tooling/ui-testing/ evidence; parent combined native smoke gate
- Next action: environment-coordination records the exhausted diagnostic stop and coordinates a working isolated virtual environment plus explicit authorization; do not retry native/device/network diagnostics

## Failure and impact

The isolated software emulator **segfaults before app launch**. The existing local record identifies emulator **37.2.13.0**, API **37.0 x86_64**, unavailable `/dev/kvm`, and a failed bounded disposable RAM-backed attempt. The app never launched in that attempt; no current-APK after-render, audio or background behavior was established.

The **one-shot diagnostic budget is exhausted**. This is a blocked validation environment, not evidence of a specific app rendering defect, and not permission for another round of emulator flags, image/config changes or device discovery.

**Native AFTER-render, audio and background validation: BLOCKED / NOT COMPLETE.** Compilation, APKs, lint, core/app tests, Robolectric/native-graphics images, component previews, accessibility trees and source review cannot turn this status into a native pass.

## Gate semantics

This bug is a **completion/validation gate, NEVER a hard source dependency**. Do not put BUG-001 in a feature's hard `Depends on` list or imply that source discovery, safe implementation, independent review and separately approved synthetic tests must wait for emulator recovery. Conversely, source landing or a successful automated gate cannot close native acceptance.

FEAT-001, FEAT-002 and FEAT-003 need real rendered interaction evidence. FEAT-004 also needs truthful audio/authority/system-control/background evidence; its disabled endpoint and outstanding critical source review are separate blockers, not explained away by this environment failure. The parent owns combined native smoke after integration, in addition to feature-level review.

## Current stop — no workarounds authorized

- No further emulator startup/configuration/diagnostic attempts or native runners, including a supposedly harmless boot-only retry.
- No ADB commands, wireless/mDNS/USB discovery, alternate device-selection fallback or renewed multicast attempts.
- No real phones or personal AVD data. A physical device is not a routine substitute or prerequisite.
- No live backend, live accounts, external network access or network diagnosis; no backend reads/writes to investigate this bug.
- No downloaded SDK/image/renderer or changed environment as an implicit retry. New native work requires explicit coordination and authorization, not interpretation of general validation instructions as an exception.

Existing synthetic in-process/loopback fixtures in separately approved automated tests are not forbidden by the native diagnostic stop; that distinction grants no build lease, external network permission or permission to run commands in this documentation task.

## Preserved evidence (not newly executed here)

- Existing diagnostic record: `.tooling/ui-testing/runs/20261103T235519Z-2/`, as documented in [testing.md](../testing.md#current-native-status-blocked--not-complete). It records prelaunch failure, not a successful app journey.
- Existing record reports `MDNS_DISABLED` and `USB_DISABLED`; denied multicast discovery was not retried. Do not retry it now.
- Before-only supplied image: `/var/home/lsm/repos/spofify-mobile/.tooling/ui-testing/reviews/20261104-user-before.png`, originally `/tmp/Spectacle.uTAxWI/Screenshot_20261104_011326.png`. This is neither an approved design nor current-APK AFTER evidence.
- [ui-validation.md](../ui-validation.md) and [mobile-ux.md](../mobile-ux.md) define evidence/review obligations. FEAT-002 supersedes their fixed-fullscreen Dialog product decision for the new sheet, not their native gate or safety policy.

These are references to the existing record; no logs/images, emulator or device were newly inspected/run by this specification. Preserve ignored evidence and clearly distinguish attempt date, tested revision and untested later changes.

## Recovery acceptance — only after explicit authorization

Open coordination question: identify an explicitly coordinated working isolated AVD environment and obtain renewed authorization. This is not a hard source or discovery dependency.

1. Environment coordination identifies a working **isolated virtual** environment, ownership boundaries and a renewed bounded native authorization. Having a proposal or finding an emulator binary is not recovery evidence. Do not perform the steps below under the present stop.
2. Use synthetic fixtures and a specifically selected isolated AVD; keep discovery/USB transports disabled and avoid personal state/live accounts. Reuse exact reviewed current-candidate APKs from an offline build gate under its separate exclusive advisory build lease per [worktree-review.md](../worktree-review.md), and record their hashes/revision. No stale-APK substitution or implicit runner build.
3. Launch the actual app and exercise Sign-in, Home, query-driven Search, Playlists and Now Playing; include loading/empty/error, taps, scrolling, IME, native Back and relevant gestures. Check narrow/short/landscape, font scale 2.0, long Unicode credits, 48dp targets and insets.
4. Validate FEAT-001 all-list Play next semantics, FEAT-002 sheet/initial controls and genuine bottom-release stability, FEAT-003 lifted-item/insertion/reflow plus held edge autoscroll, and FEAT-004 audio/authority/lifecycle/system controls to the extent its implementation is ready. A fitting queue or static image cannot establish a scroll/drag regression fix.
5. Capture and **actually inspect** current-APK after-renders and interaction evidence. Record device/image/API/viewport/density/font scale, candidate/APK hashes, actions, screenshot paths, audio/background observations, findings, fixes and independent reviewer. Answer the rendered-review checklist, including row counts/uniformity, IME/chrome budget and actionable versus metadata targets. Delegated implementers/reviewers must inspect relevant renders; parent runs combined smoke.
6. Resolve/re-capture regressions before claiming a pass. Record precise hardware-only uncertainties as unverified and coordinate any exceptional check through the user; no direct phone access. Report partial coverage honestly.

## Closure rule and risks

Do not close solely because the emulator boots, a sign-in screenshot exists, source is merged or automated tests pass. Record environment recovery separately from completed feature acceptance; outstanding native AFTER/audio/background journeys keep the relevant completion gates open. A new failed attempt requires the same explicit blocked/not-complete report, not an endless fallback loop.

The principal risks are unauthorized diagnostic repetition, accidentally discovering/targeting personal devices, exposing live credentials, approving before-only or stale screenshots, and converting a validation blocker into an artificial source dependency. No implementation or recovery is claimed by this document.
