# Settings and appearance

Settings now exposes Account and server, Account colours, Light theme, and Credits → Font license. The bundled JetBrains Mono license requires no network access.

Light/dark is one device-local preference, stored separately from authentication. Writes are acknowledged only after the platform commit succeeds; failures retain the previous displayed mode and show a notice.

Account palettes use the documented `GET/PUT /api/me/palette` contract. Only the validated v1 Jade, Ember and Violet catalog is accepted. Palette state belongs to canonical origin, immutable account ID and session incarnation. Rendering checks the live authenticated owner as well as the controller's asynchronous publication fence. Sign-out, account changes and session retirement remove effective account colours immediately.

A choice previews its palette while the revisioned PUT is pending. Failure rolls back the preview and reconciles with a same-owner GET. It never replays the PUT or queues a choice. If reconciliation fails, the last confirmed palette remains labelled and further choices are disabled until a successful reload. Account palettes have no global or persisted local fallback.

Palette tokens only replace primary/on-primary, secondary and tertiary roles. Light/dark owns neutral surfaces and text. Appearance changes do not replace the navigation graph, reconnect playback, or issue musical commands.

Offline mode, temporary cache controls and Saved music are not enabled by this slice. They require the verified download/store and local playback owners; semantic palette preview text does not claim those features exist.
