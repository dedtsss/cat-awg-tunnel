# CAT AWG Tunnel Maestro UI pilot

This is an audit-only CAT suite for Product PR #1. It covers functional reachability and
visual evidence for the Cat-added screens without changing product behavior.

## Run

The suite is intended to run against the exact-head APK produced by GitHub Actions:

```text
maestro cloud <exact-head-apk> .maestro/flows \
  --apiKey <runtime secret> --projectId <runtime secret> \
  --repoOwner dedtsss --repoName cat-awg-tunnel \
  --branch codex/goal-a-cat-awg-android \
  --commitSha e21eb2d55e3eb1b7dd81ee1ed25971c34432d026 \
  --pullRequestId 1 --device-model pixel_6 --device-os android-35 \
  --format JUNIT --output build/cat-ui/report.xml
```

Do not put cloud credentials in this file, CI artifacts, screenshots or reports. The three
flows use only safe local/offline states; they do not assert a VPN handshake or server reachability.

## Coverage matrix

| ID | Surface/state | Functional check | Visual evidence | Classification if blocked/failing |
| --- | --- | --- | --- | --- |
| CAT-01 | Settings Cat entry points | reach Client diagnostics, Cat Server, Configurator | `cat-01-settings` | `TEST_BUG` / `CLOUD_LIMITATION` |
| CAT-02 | Cat Server unpaired | status and start card | `cat-02-cat-server-start` | `APP_BUG` only with correlated screenshot/hierarchy |
| CAT-03 | Cat Server invalid payload | user-facing invalid-payload error | `cat-05-invalid-pairing` | `APP_BUG` only with correlated evidence |
| CAT-04 | Cat Server manual form | expand fields and offline error path | `cat-06-manual-form`, `cat-07-manual-offline-error` | `APP_BUG` only with correlated evidence |
| CAT-05 | AWG Configurator initial | AWG3 capability-gated state | `cat-03-configurator-start` | `APP_BUG` only with correlated evidence |
| CAT-06 | AWG Configurator invalid | keyboard shift and local validation | `cat-08-configurator-keyboard`, `cat-09-configurator-invalid` | `APP_BUG` only with correlated evidence |
| CAT-07 | AWG Configurator valid/save | deterministic validation and candidate save | `cat-10-configurator-valid`, `cat-11-configurator-saved` | `APP_BUG` only with correlated evidence |
| CAT-08 | Diagnostics standalone | offline diagnostics state and back navigation | `cat-04-diagnostics-offline` | `APP_BUG` only with correlated evidence |
| CAT-09 | About/support provenance | product identity and git SHA are visible | `cat-12-support-provenance` | `APP_BUG` only with correlated evidence |

## Evidence rule

Functional PASS does not imply visual PASS. Every screenshot must be inspected for overlap,
clipping, hidden controls, keyboard occlusion, hierarchy, spacing, and narrow-layout overflow.
Any suspected visual failure remains `UNKNOWN` until correlated with hierarchy/geometry or
reproducible behavior; only then can it become `APP_BUG`.
