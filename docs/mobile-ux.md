# Mobile UX — compatibility pointer and technical references

Retained for historical task links, including the rendered-review fragment below. Product-specific UX and rendered-review practices now live in hmem: **Music-first Android UX and rendered review**, project `fb35f4ae-37f7-43e2-a4e6-d9355947bc01` in the Mobile workspace. Playback exceptions live in **Playback authority and narrow policy exceptions** `b496bd3b-9f34-4a96-af90-8544bf861c69`.

Use the [hmem bootstrap](../AGENTS.md#hmem-bootstrap) and read current project/task details. Historical task directions to revise local UX policy are superseded; this file is not a parallel requirements or acceptance record.

### Rendered-review questions — answer with evidence, not source assertions

Compatibility anchor used by retained task specs. The current checklist is in hmem topic `fb35f4ae-37f7-43e2-a4e6-d9355947bc01`; native validation practice is in `893c1026-8b6c-4d80-934c-cac7987e8c6d`. The historical before-only baseline and native evidence locations remain in [testing.md](testing.md#native-evidence-status), not proof of current acceptance.

## Technical source references

The backend is canonically **spofify-server**, locally `spofify-go`. The following are sibling-checkout technical references, not files supplied by this checkout or claims of deployed capability:

- [Shared music-item primitives](../../spofify-go/docs/frontend.md#shared-music-item-ui-standard): website `mediaCard` / `--music-*` geometry and action slots use web units, not Android dp.
- [Library search](../../spofify-go/docs/frontend.md#library-search): a bounded alphabetically paged response alone cannot establish global relevance ranking. Exact/prefix/name matching within a candidate set is distinct from server-side ranking.
- [Loading interface](../../spofify-go/docs/frontend.md#shared-loading-ui-standard).
- [Personal library and collections](../../spofify-go/docs/frontend.md#personal-library-and-collection-navigation).

These source links preserve technical context; current Android product decisions are in hmem rather than a copied website-to-native policy table.

## Android references

- [Navigation principles](https://developer.android.com/guide/navigation/principles)
- [Predictive Back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Compose accessibility defaults and minimum targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets)
- [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive)
- [State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)
- [Compose testing](https://developer.android.com/develop/ui/compose/testing)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
