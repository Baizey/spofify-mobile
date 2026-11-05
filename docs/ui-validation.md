# Native validation — compatibility pointer and runner reference

Retained for historical task references and the technical runner interface below. Current native safety, resource ownership, screenshot review and completion practices live in hmem: **Isolated Android native validation and evidence** `893c1026-8b6c-4d80-934c-cac7987e8c6d` and **Build/native ownership, storage and cleanup** `c49a1930-06b9-46bf-96ae-882f60f2fe6b` in the Mobile workspace. Use the [hmem bootstrap](../AGENTS.md#hmem-bootstrap) and read current project details before execution; this document is not the practice authority.

## Technical runner interface

`tools/ui/validate-native.sh` uses the owned `spofify_visual_test` AVD, dedicated ADB port **5039** and emulator ports **5560/5561**. It requires installed Android command-line tools, emulator and a compatible system image; it does not download packages or alter personal AVDs automatically.

- `ANDROID_HOME` selects the installed SDK; `UI_TEST_SYSTEM_IMAGE` selects an installed image package.
- `UI_TEST_GPU` selects a supported renderer; `UI_TEST_BOOT_TIMEOUT` sets the bounded boot wait.
- Optional `UI_TEST_RAM_RUNTIME=1` creates its own disposable `/dev/shm/spofify-ui.*` workspace instead of `.tooling/ui-testing/avd/`.
- `--skip-build` reuses existing APKs. AGP may place them under `outputs/` or `intermediates/`; discovery of the newest matching artifacts does not prove they match a candidate SHA.
- `--boot-smoke` launches and captures Sign-in only, not the full journey or completion gate.
- `UI_TEST_CLASS` selects the instrumentation class; the default is `dev.spofify.mobile.ui.NativeUiSmokeTest`.

The runner writes this ignored output interface:

```text
.tooling/ui-testing/runs/<timestamp>-<run-id>/
  evidence.md
  working-tree.txt
  adb-server-status.txt
  emulator.log
  instrumentation.log
  screenshots/
```

`.tooling/ui-testing/latest-run.txt` points to the newest attempt. A failed run can contain partial screenshots; generated reports are artifacts, not a handwritten planning database or proof of acceptance. Historical native evidence locations are preserved in [testing.md](testing.md#native-evidence-status).

## ADB discovery reference

`224.0.0.251:5353` and `[ff02::fb]:5353` are mDNS multicast discovery destinations. ADB supports discovery/transport disable switches in upstream source: [USB initialization](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/main.cpp) and [mDNS enable check](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/mdns_utils.cpp). `ADB_MDNS_AUTO_CONNECT=0` alone disables auto-connect, **not discovery itself**; environment changes do not reconfigure an already-running server. The current operational restrictions belong to the hmem native topic, not this reference note.
