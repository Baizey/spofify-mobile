# Recovery 08 — Android headset/system transport and volume

Outcome: reliable existing MediaSessionService controls, not another player or Activity key dispatcher.
Branch suggestion: `recover/android-media-controls`. Consume landed 06 start-safety contract; **no dependency on 07 queue feature or UI polish**. Exact extraction: [00-extraction](00-extraction.md), section 08. Original FEAT-103 behavior/security requirements apply, not historical centralized integration instructions.

## Scope
- Narrow service key trust/admission/dedup/time fencing; immediate single distinct down policy, up/repeat consumed, supported Play/Pause/Toggle/Stop-as-Pause/Next/Previous/FF/rewind and safe unknown-key delegation.
- Prepared-only fenced coordinated transport: media Play resumes actual in-memory current item/position, never initializes/imports a server/restored queue or starts local audio under remote ownership.
- Authoritative read-only remote session projection/getters/commands/events and valid remote notification state, with no audio URI/local history/foreground promotion. Recover R forward-seek event and remote error notification fixes/tests together with this feature.
- Standard Activity STREAM_MUSIC routing; volume stays OS stream/route, not player gain/remote command. Do not broaden raw mutators, exported surfaces, unknown caller trust or permissions.
- Exclude queue/manual FIFO/Play-next result machinery and tests mistakenly present in the old media lane; 07 owns those. Keep the required `preparedTransportCommand` independent of queue-result callbacks.

## Minimum acceptance / open limitations
- Actual service callback/controller regressions for trust, unknown/malformed/down/up/repeat/dedup, stale/auth/output/queue/service changes, pause self-cancellation, prepared-only resume and bounded seek. Projection/notification tests cover empty/stale local output, remote errors/buffering, occurrence timeline mapping and event publication.
- API26–27 legacy unknown UID deliberately fails closed; record unresolved compatibility explicitly. No universal headset multi-click/AVRCP/OEM claim.
- Run full leased offline gate and native **NativeMediaControlsTest selected explicitly with fresh synthetic app data**; default runner only selects smoke. Exercise Android key/controller/actual notification intents, OS volume indices, background/screen-off/recreation and inspect current-APK captures; distinguish synthetic progress/delivery from acoustic/hardware proof.
- Replace obsolete central-owner/workflow statements only in scoped current behavior docs; do not rewrite old specs/history. Independent exact-SHA/base/security/render review and own merge as ready.

R tests and dirty-origin fixes have no fresh full/native pass. Keep precise blockers with this lead; do not wait for unrelated UI features or turn shared PlaybackService edits into a combined batch.
