# Settings and appearance

Settings exposes Account and server, Account colours, and About → Credits → Font license. The bundled JetBrains Mono license requires no network access. The app always uses its dark theme; any previously saved device theme preference is ignored.

Account palettes use the documented `GET/PUT /api/me/palette` contract. Only the validated v1 Jade, Ember and Violet catalog is accepted. Palette state belongs to canonical origin, immutable account ID and session incarnation. Rendering checks the live authenticated owner as well as the controller's asynchronous publication fence. Sign-out, account changes and session retirement remove effective account colours immediately.

A choice previews its palette while the revisioned PUT is pending. Save/refresh state is announced accessibly without inserting a progress bar or shifting the choices. Selection underlines also reserve a fixed height so choosing a different row does not move its neighbours. An initial read without a catalog still has an explicit loading label. The interactive reference mirrors the stable choice geometry; its account preferences remain synthetic fixtures. Failure rolls back the preview and reconciles with a same-owner GET. It never replays the PUT or queues a choice. If reconciliation fails, the last confirmed palette remains labelled and further choices are disabled until a successful reload. Account palettes have no global or persisted local fallback.

Account colours use the dark tokens from each preset. They only replace primary/on-primary, secondary and tertiary roles; neutral surfaces and text stay dark-themed. The v1 palette response format is unchanged. Palette changes do not replace the navigation graph, reconnect playback, or issue musical commands.

The [wireframe palette study](reference/index.html) explores a different mapping: the dark canvas and all panels stay neutral. Primary colours main actions, secondary colours active controls and focus, and tertiary is reserved for brief emphasis. Its Oxblood, Pine and Ink Blue presets are review fixtures only, not changes to the native palette catalog.
