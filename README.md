# Spofify for Android

Native Kotlin / Jetpack Compose client. This source implements connection, explicit
sign-in, validated session restoration and Account / Sign out. There are no placeholder
music destinations. The Android-free `:core` owns origin, HTTP and session contracts;
`:app` owns platform encryption, lifecycle and presentation.

## UI specification

The agreed, iterated visual and interaction specification is
[the interactive wireframe](docs/reference/index.html). Open it directly in a browser;
it is self-contained and works offline. Its final rendered screens and touch behavior
are the specification, not suggestions. The file contains cumulative renderer overrides,
so earlier function definitions alone do not describe the final UI. Its synthetic music,
accounts and playback are fixtures, not production API contracts.

## Development

Open this directory in Android Studio. Use JDK 25, Android platform 37.0 and
build-tools 36.0.0. The version catalog pins AGP 9.3.1, Kotlin / Compose compiler
2.4.21. The wrapper retains Gradle 9.9.0 from main. AGP built-in Kotlin consumes
the newer KGP loaded by the root JVM plugin; no second Android Kotlin plugin is
applied. Sources target Java 17; runtime minimum is Android 8 (API 26), target API 36.

The login slice was source-reviewed with a Gradle 9.6.0 pin. Retaining main’s
9.9.0 wrapper is an explicit integration choice, not a verified compatibility claim.
It is beyond Kotlin 2.4.21’s previously checked fully tested Gradle range (through
9.7.0). Compilation, tests and APK execution remain unverified; the agent build
attempt was blocked by policy before wrapper bootstrap.

On an environment with these dependencies legitimately provisioned:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Use `--offline` only when dependencies and the wrapper distribution are already
provisioned; it does not prevent wrapper bootstrap. Independent builds need separate
writable Gradle homes and temporary state. No cache repair, download workaround or
toolchain installation is provided by this repository.

Debug/release are standard Android Studio build types, both with application ID
`dev.spofify.mobile`. Release signing is not configured here. Debug signing is the
normal Android tooling default. This does not establish compatibility with an
already installed application's signature; do not uninstall, clear data, replace keys
or change signing material to work around an incompatibility.

## Contracts

See [authentication](docs/authentication.md) for transport, state ownership and
installed storage compatibility. JetBrains Mono is bundled from the previously
approved public [JetBrains Mono 2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304) source; its complete OFL is in
`app/src/main/assets/licenses/JetBrainsMono-OFL.txt` and locally accessible from Account.
Fonts retain the approved existing regular/medium/semibold/bold bytes.

Unit/contract checks do not establish rendered UI, Keystore behavior on a device,
installation compatibility or real-server operation.
