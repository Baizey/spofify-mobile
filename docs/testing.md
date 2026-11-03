# Validation

## Automated

Run:

    ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

Core tests use MockWebServer, never the live backend or real accounts. They cover origin/IP validation, opaque ID encoding, listener/admin availability, cookie policy/expiry/scoping, Origin/CSRF, session generation races/cancellation, redirects, one-shot mutations, pagination and personal DTO normalization.

The latest local gate passed **43 core tests and 22 app tests**, produced the debug APK, and completed lint with zero errors. Warnings remain for pinned dependency versions, deliberate private-network HTTP and preference API suggestions.

Android tests cover real Compose Activity launch/sign-in validation using Robolectric, the HTTP warning, controlled search resets, cross-client session ownership, transient connection retry, pending logout/expiry, canceled queue loads, playlist occurrence selection, like-read/write ordering, task-removal policy and the pure history-progress gate. They are not physical-device playback tests. Lint's cleartext warning is intentional for user-configured private-network HTTP; runtime origin validation is the additional restriction Android XML cannot express as IP ranges.

On this JDK 25 host, Robolectric prints a native-runtime temporary-directory cleanup diagnostic after the tests; the test reports and Gradle gate still pass. CI uses JDK 21. No visual screenshot or physical-device playback validation is claimed.

## Device acceptance before release

Use a coordinated, disposable listener account and test library; do not mutate live accounts without permission.

1. Connect over trusted HTTPS and permitted LAN/Tailscale HTTP. Wrong hosts, public HTTP, paths, bad TLS and redirects fail safely.
2. Sign in as listener and admin. No management UI/actions appear. Kill/reopen and revalidate the encrypted session; never autoplay.
3. Browse/search/paginate with empty results, long names, Unicode IDs, missing art/audio and slow/unreachable servers. Navigate back while requests are pending.
4. Play an album longer than 40 tracks and a capped context; select a late track explicitly. Test likes and manual playlists, including unavailable and duplicate entries.
5. Play supported MP3/FLAC/AAC/Opus files. Seek repeatedly; inspect server requests to confirm each range remains authenticated. Missing/unsupported audio stops without looping.
6. Rotate the screen, navigate away, lock the phone and leave the Activity. Audio should continue with notification/lock-screen/Bluetooth controls, stop on headphone unplug, and respect competing audio focus.
7. Verify previous/restart, next, queue selection, shuffle, repeat-one/all and seek elapsed/duration. Dismiss Now Playing with Back or a downward sheet gesture; playback, position and queue must remain unchanged. There is no normal-listening Close player action.
8. A like persists server-side. History appears once per queue occurrence after actual progress, never just preparing/seeking. Network failure in history does not interrupt audio.
9. Sign out or expire/revoke the session during playback and a pending queue load. Buffered playback and private content must stop; old requests must not resurrect a session or affect a new server/account.
10. Restart the process/service. The old queue must not autoplay. Confirm cloud backup/device transfer excludes sessions and no plaintext credential, audio or image disk cache exists.
11. Check talkback labels, large fonts, narrow/landscape layouts, touch targets, keyboard/IME and navigation bars.

Cross-device synchronization, Android Auto, offline support, playlist editing and store signing have not been implemented or validated.
