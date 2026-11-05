# Validation — compatibility pointer and historical evidence

Retained for inbound task references. Current validation practices live in hmem in the Mobile workspace:

- **Toolchain, automated gates and provisioned fixtures** — `bbe6b0dd-cd65-44d6-8226-c822701a79da`.
- **Build/native ownership, storage and cleanup** — `c49a1930-06b9-46bf-96ae-882f60f2fe6b`.
- **Isolated Android native validation and evidence** — `893c1026-8b6c-4d80-934c-cac7987e8c6d`.

Use the [hmem bootstrap](../AGENTS.md#hmem-bootstrap) and read current project details, rather than treating historical task instructions as a local workflow. Technical fixture contracts remain in [prepared transport testing](prepared-transport-testing.md) and the [broker fixture documentation](../tools/testing/prepared-transport-broker/README.md).

### Native evidence status

The following is retained historical evidence from the pre-cleanup documentation, **not current environment diagnosis or candidate acceptance**.

Native after-render/audio/background acceptance remains **not complete** in the evidence available to this documentation cleanup. Earlier attempts with emulator 37.2.13.0 and an API 37.0 x86_64 image reportedly segfaulted before app launch under software emulation, including a disposable RAM-backed attempt. The earlier report found no usable `/dev/kvm`; these are historical observations, not verified current environment facts or proof that all AVDs fail.

Historical diagnostic evidence was recorded under `.tooling/ui-testing/runs/20261103T235519Z-2/`; the user-supplied before-only image was preserved at `.tooling/ui-testing/reviews/20261104-user-before.png` in the root checkout. These ignored local artifacts may not exist in every checkout. Neither is current-APK after-validation. This cleanup ran no emulator or toolchain diagnosis and establishes no successful native pass.

The historical user-supplied **before-only** image was reported to show visible Artists/Albums count rows, artist-dependent row height with stacked credits, unused top chrome and a rounded inset mini-player. These are preserved historical observations from the earlier documentation, not findings about the current candidate, an approved design or after-render acceptance; this cleanup did not inspect the ignored image.
