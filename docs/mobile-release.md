# Mobile release operations

Like Desktop, Mobile packages from canonical `main` and publishes to its own public [GitHub Releases](https://github.com/Baizey/spofify-mobile/releases). The manual workflow packages an unsigned APK without secrets, then signs and publishes it in one protected `mobile-release` job. Only that job grants the built-in `GITHUB_TOKEN` `contents: write`. Ordinary CI does not publish and needs no secrets.

Debug remains `dev.spofify.mobile` with its existing Android debug signing and data. Release is `dev.spofify.mobile.release` with its own signer and data. Nothing migrates or copies between the two apps.

## One-time bootstrap (operator only)

1. On the public `Baizey/spofify-mobile` repository, protect `main` with reviewed changes, including the workflow, scripts, build and certificate anchor. Configure `mobile-release` **before adding secrets**: restrict it to `main`, require an independent reviewer and prevent self-review/admin bypass where available. The workflow cannot enforce environment settings by itself. Do not use unprotected secrets.
2. Publish mobile updates only through this workflow. Protect `mobile-v…` tags and published assets against edits and deletion where available, without blocking the workflow token from creating releases. Never delete a release and its tag: GitHub would no longer report that the version code was used.
3. Privately generate and back up a dedicated, long-lived Android app-signing key in a PKCS#12 keystore. For example, in a private directory on a trusted workstation, use `keytool -genkeypair -storetype PKCS12 -keystore mobile-release.p12 -alias spofify-mobile-release -keyalg RSA -keysize 3072 -validity 10000` and enter an appropriate name and strong password interactively. Do not use the standard Android debug key, a disposable fixture key, or a server key. Maintain tested encrypted offline backups and accountable access. Use your keytool's PKCS#12 password conventions (typically the key password equals the store password).
4. Export **only the public certificate** and compute its SHA-256 over DER. Confirm it independently with the certificate exported from the standing keystore. Commit the 64 hex digits alone to [`scripts/mobile-release-cert.sha256`](../scripts/mobile-release-cert.sha256) in a reviewed change before dispatch. `UNCONFIGURED` deliberately blocks official releases. Do not update the anchor to make a failed signing job pass; losing the standing key or changing signers requires a separate Android update-compatibility plan.

   ```sh
   keytool -exportcert -storetype PKCS12 -keystore mobile-release.p12 \
     -alias spofify-mobile-release -file mobile-release-cert.der
   sha256sum mobile-release-cert.der
   ```

5. In `mobile-release` only, provision secrets `MOBILE_KEYSTORE_B64` (single-line base64 of the PKCS#12 file), `MOBILE_KEYSTORE_PASSWORD`, `MOBILE_KEY_ALIAS` and `MOBILE_KEY_PASSWORD`. Do not print or commit the base64, keystore or passwords. Transfer them through the operator's approved secret-management channel. No separate GitHub credential is needed.

## How in-app updating works

The release app checks the anonymous latest release on foreground entry or return from Work offline, not on a background timer. A non-APK release such as `ci-broker-b6eb54f` is not an update. Completed checks are normally held for 30 minutes. A verified update stays ready without polling across foreground returns until the process restarts, Work offline fences it, or it fails revalidation. Installation requires a new explicit tap from Settings while the app is resumed and not playing locally, then Android's own confirmation. Debug has no update surface or installer permission.

Release records Work offline in its own versioned private policy file; it does not load or migrate the debug preference. Malformed or interrupted state starts offline, preserving local access; an explicit return online rewrites the policy. An unreadable policy fails closed. Test failure and recovery with synthetic app data only.

## Publish a stable release

1. Complete the device acceptance gate before authorizing rollout. Manually run **Publish Mobile Release** on `Baizey/spofify-mobile` `main`. Supply a strictly increasing decimal `version_code` (`1`–`2100000000`, no leading zeroes) and display `version_name` (1–80 ASCII letters/digits or `.+_-`, beginning with a letter or digit). Gradle applies both to release only. The stable tag is `mobile-v<versionCode>`; the display name does not determine ordering. The package job repeats Mobile CI checks on the exact dispatch SHA, including both variants and disposable signing.
2. At the `mobile-release` approval, review the dispatch SHA, inputs and successful package job, with the unsigned artifact still available (one-day retention). Cancel stale approvals; subsequent changes to `main` do not change the packaged revision. Reruns are blocked; inspect for partial publication before starting a new dispatch.
3. The publish job verifies the signed APK's identity, requested version and certificate, and checks all paginated canonical tags and releases (including drafts and prereleases) plus anonymous latest. Unreserved tags such as `ci-broker-b6eb54f` are ignored. Duplicate, reused or lower codes and malformed `mobile-v…` entries block publication. The job uploads exactly one APK to a draft, checks GitHub's own digest and size, then rechecks history. It creates a new lightweight tag at the exact packaged SHA, never reuses a tag, and promotes the draft to stable latest. GitHub's automatic source archives are not uploaded assets. Workflow concurrency serializes dispatches; do not publish out of band.
4. Confirm job success and anonymous `GET https://api.github.com/repos/Baizey/spofify-mobile/releases/latest`: `draft=false`, `prerelease=false`, tag `mobile-v<versionCode>`, and exactly one uploaded asset `spofify-mobile-release.apk` with positive exact `size` ≤ 150 MiB, `digest` `sha256:<64 hex>`, and URL `https://github.com/Baizey/spofify-mobile/releases/download/<tag>/spofify-mobile-release.apk`. The workflow polls this endpoint with a 128 KiB response cap, verifies the tag SHA, then anonymously streams the canonical download and checks byte count and SHA-256 against the signed APK. It follows only bounded HTTPS redirects to the exact `release-assets.githubusercontent.com` host.

If the draft digest never appears, the workflow fails and leaves the release a draft. Do not publish it manually or fill the digest yourself. If publication was attempted but final anonymous verification failed, the release may already be public. Investigate the release, asset and tag before making any changes. Do not retry a failed publication blindly, overwrite assets, remove a published tag, reuse a code, or change `latest` to roll back. After addressing the cause, only ship a correction with a higher code. Untouched failed drafts intentionally reserve their code.

## Offline checks and device acceptance

With Java 25, Android SDK platform `platforms;android-37.0` and build-tools 36.0.0 provisioned, CI and operators can verify the build without release credentials. The hosted build jobs use Temurin 25 to start Gradle and SDK tools; the Gradle daemon is pinned separately to JetBrains Runtime 25 by `gradle/gradle-daemon-jvm.properties`. A fresh build runner without matching JBR 25 must be able to reach Foojay and its JBR download destination. For offline builds, provision JBR 25 and the wrapper/dependencies before starting; having Temurin alone is not enough.

```sh
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
(
  export ANDROID_USER_HOME=$(mktemp -d)
  trap 'rm -rf -- "$ANDROID_USER_HOME"' EXIT
  ./gradlew --no-daemon -PmobileReleaseVersionCode=12345 -PmobileReleaseVersionName=0.0.0-ci \
    :core:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
  bash scripts/test-mobile-signing.sh
)
```

The signing test uses fresh disposable keys and asserts debug identity, release identity/version, APK verification and wrong-signer rejection. Publication fixtures use mocked responses and perform no GitHub operations. None of these checks proves the hosted runner, protected environment settings, real GitHub CDN routing or a real publication; use hosted CI and operator review to validate prerequisites. No emulator was available for pipeline verification; no installation was performed.

Before authorizing the first official release, on an isolated Android emulator/device profile, use two release builds signed with the **same disposable certificate** and increasing version codes, synthetic release metadata and synthetic local app data only. Confirm side-by-side debug and release, no copied data, and that the new release installs **over** the previous release without clearing app data. Verify version advances, the release's synthetic preferences/offline data remain, and the unrelated debug installation/data remain untouched. Confirm mismatched signer and downgrade are rejected. Check that switching to Work offline fences update work; if the persistence write fails it stays fenced until a successful offline→online transition or a subsequent app process successfully reads online state. For the installer handoff, verify the installer does not launch automatically, Android alone owns source approval and explicit install confirmation, and cancelling either prompt leaves the app/data unchanged. Do not use an existing user installation, standing key or live account for this acceptance gate.

References: [GitHub releases REST API](https://docs.github.com/en/rest/releases/releases), [release assets API](https://docs.github.com/en/rest/releases/assets), [deployment environments](https://docs.github.com/en/actions/deployment/targeting-different-environments/managing-environments-for-deployment), [Android versioning](https://developer.android.com/studio/publish/versioning), [APK signing and verification](https://developer.android.com/tools/apksigner).
