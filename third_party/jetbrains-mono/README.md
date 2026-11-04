# Bundled JetBrains Mono

## Provenance and license

- Official repository: <https://github.com/JetBrains/JetBrainsMono>
- Pinned release: **v2.304**, <https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304>
- Verified release tree: `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9` from
  <https://api.github.com/repos/JetBrains/JetBrainsMono/git/trees/v2.304?recursive=1>.
- Original license and copyright notice: [`OFL.txt`](OFL.txt), copied **byte-for-byte** from
  <https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/OFL.txt>.
  This is the **SIL Open Font License, Version 1.1**. The upstream notice reads:

  > Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)

The four fonts are unmodified official static TrueType files, renamed only for
Android resource naming. All embedded upstream copyright, license and other name
table records are retained. The font name table reports `Version 2.304;
ttfautohint (v1.8.4.7-5d5b)`. No subsetting, re-encoding, variable-font conversion,
new dependencies, font providers or runtime network downloads are involved.
Retain the original `OFL.txt` and notices when redistributing these assets.

## Exact upstream files and Android resources

| Weight | Android resource / path relative to `app/src/main/res/font/` | Bytes | Exact official download |
| --- | --- | ---: | --- |
| 400 / Normal | `jetbrains_mono_regular.ttf` | 273900 | <https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-Regular.ttf> |
| 500 / Medium | `jetbrains_mono_medium.ttf` | 273860 | <https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-Medium.ttf> |
| 600 / SemiBold | `jetbrains_mono_semibold.ttf` | 277092 | <https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-SemiBold.ttf> |
| 700 / Bold | `jetbrains_mono_bold.ttf` | 277828 | <https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/ttf/JetBrainsMono-Bold.ttf> |

All four faces map to `FontStyle.Normal`. Material's standard style weights are
preserved; medium, semibold and bold are available to explicit weight overrides
without substituting a different family.

## Verification

Downloads returned HTTP 200; TTF responses were `application/octet-stream` and
the license was `text/plain; charset=utf-8`, not HTML. Before saving, each asset's
size and Git blob SHA-1 were checked against the official release tree above.
Each TTF has sfnt magic `00 01 00 00`, 17 bounded tables including `head`, `cmap`,
`name`, `glyf` and `OS/2`, and its `OS/2.usWeightClass` matches 400/500/600/700.
The actual downloaded upstream `OFL.txt` includes the copyright notice and full
SIL OFL 1.1 text; it has not been reconstructed or replaced with a summary.

[`SHA256SUMS`](SHA256SUMS) records SHA-256 for all four fonts and the original
license, using paths relative to the repository root. Verify locally from that
root without network access:

```sh
sha256sum --check third_party/jetbrains-mono/SHA256SUMS
```

## Compose integration and text behavior

Public entry points in package `dev.spofify.mobile.ui.theme`:

- `JetBrainsMono: FontFamily`, the four resource-backed static faces.
- `AppTypography: Typography`, Material 3 `Typography()` defaults with only
  `fontFamily` changed on all 15 display/headline/title/body/label styles.

The parent owns root integration via `MaterialTheme(typography = AppTypography,
...)`; this scoped change does not edit the Activity or root graph. Sizes,
weights, line heights, tracking, platform text options and font feature settings
are preserved, not redesigned.

These are the standard JetBrains Mono faces, not the NL variant. Compose/Android
retain their normal text shaping and ligature behavior: there is no global
`fontFeatureSettings` override enabling discretionary ligatures or disabling
standard ones. Text/metadata is not rewritten. Unsupported Unicode and emoji
continue through Android's system font fallback; the bundled family is not a
promise of coverage for every script or emoji. Long names, mixed scripts, emoji
and ligatures must be reviewed in actual app rendering, not inferred from glyph
pixels in a JVM test.

## Validation status

`app/src/test/java/dev/spofify/mobile/ui/theme/TypographyTest.kt` checks all 15
styles use the bundled family, all other default style properties are unchanged,
and the four normal-style resource names and weights map as intended. These
focused tests have **not been run** in this scoped task: the parent owns the
Gradle build slot. No Gradle/dependency changes are needed.

**UI validation blocked / not complete:** the reported AVD startup fails with a
TCG crash. No emulator or ADB operation was attempted in this scoped task. The
parent must integrate the theme, run the automated gate, and capture/inspect
actual screenshots (including narrow viewport, large font scale, wrapping,
long/Unicode music metadata, and the required combined native flows) before
claiming visual readiness. Unit tests and font-file verification do not establish
visual quality. Use isolated virtual devices only; any exceptional hardware
check must be coordinated through the user.
