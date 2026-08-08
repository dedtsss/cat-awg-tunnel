# Baseline — 2026-08-08

## Source used

- Upstream remote: `https://github.com/wgtunnel/android.git`
- Branch inspected: `master`
- Exact base: `d349aeef52fd83bfb755edc9637abfe049d280dd` (`2026-08-02`, `chore(deps): bump navigation3, ktor client`)
- Upstream release observed: `5.2.1` (`2026-07-23`); a `nightly` pre-release was also present.
- Upstream license: MIT. The upstream `LICENSE` and notices remain intact.
- Upstream issue rechecked before implementation: [wgtunnel/android#1232](https://github.com/wgtunnel/android/issues/1232) was open with no implementation PR or comment that supplied this feature.

`upstream` remains configured as a read-only collaboration remote. `origin` is the Cat fork; no write was made to upstream.

## Architecture reviewed

The baseline is a Gradle multi-module Android project. Relevant modules and files were reviewed before the Cat work:

- `app`: Room persistence, Koin modules, Compose Navigation 3 screens, split-by-app settings, configuration editor, coordination layer.
- `tunnel`: `Backend`, recovery/DDNS flows, `TunnelEvent`, and `service/VpnService.kt`; `createTunInterface()` is the VPN builder integration point.
- `networkmonitor`: underlying-network state and permission-aware monitoring.
- `logcatter`: local app log facilities, used as an existing diagnostic source rather than introducing a second raw logger.
- configuration parser/model and AWG editor/range behavior in the upstream app.

## Build baseline

The initial clean-source build required a local JDK because the environment contained a JRE only. It also required Android platform 37, NDK `28.2.13676358`, and CMake `3.22.1`. Those were installed locally; no project dependency was changed merely to accommodate the environment.

```text
JAVA_HOME=/tmp/cat-awg-jdk-Svhjbo/jdk-21.0.12+8
ANDROID_HOME=/home/codex/Android/Sdk
./gradlew :app:compileStandaloneDebugKotlin --no-daemon --no-parallel --max-workers=1
```

Result on the clean base: PASS. The VPS now supplies bounded global Gradle/Kotlin limits; project memory settings were not raised or changed. Existing upstream compiler warnings (nullable Java address results and Android capability checks) were observed but were not treated as Cat regressions.

The baseline test inventory is intentionally small (`app/src/test/.../ExampleUnitTest.kt` plus module tests). Device/emulator E2E was not available in this environment; final verification documents the resulting limitation rather than claiming a handshake or browser share flow was physically exercised.

## Final verification

All final Gradle invocations were serialized with `--no-daemon --no-parallel --max-workers=1` and the VPS-provided 1 GiB Gradle heap / 512 MiB metaspace cap.

- `:catcore:test`: PASS, 20 JVM tests (routing, diagnostics, configurator, protocol fixtures).
- `:app:testStandaloneDebugUnitTest`: PASS (the upstream app unit-test inventory remains one test).
- `:app:assembleStandaloneDebug`: PASS; standalone debug APK produced after native submodules were initialized.
- `:app:lintStandaloneDebug`: PASS; no new issues. The committed baseline filters 62 inherited upstream errors, 95 inherited warnings, and one hint so future Cat changes are still linted.
- `:app:compileStandaloneReleaseKotlin`: PASS. A signed release APK cannot be produced on this runner because neither a keystore nor signing environment variables are present.

Physical-device/emulator VPN handshakes, Chrome/Firefox share-sheet interaction, and actual browser traffic routing were not run. Parser, lifecycle, route-planning, persistence, and export behavior are covered by JVM/code-level checks instead.
