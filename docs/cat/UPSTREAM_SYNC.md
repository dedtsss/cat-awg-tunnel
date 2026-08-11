# Upstream synchronization

```text
origin   https://github.com/dedtsss/cat-awg-tunnel.git
upstream https://github.com/wgtunnel/android.git
```

Fetch upstream regularly, create a temporary integration branch, and rebase or merge `upstream/master` into the Cat branch. Do not push to `upstream`; publish only `origin` branches and review the resulting diff before a Cat PR is merged.

After a fresh clone or upstream integration, initialize the native dependencies before packaging an APK:

```text
git submodule update --init --recursive
```

## Expected conflict areas

- `tunnel/.../VpnService.kt`: one small API-33 exclusion snapshot hook before `establish()`.
- `app/.../TunnelCoordinator.kt`, Koin modules, and `WireGuardAutoTunnel.kt`: Cat lifecycle/DI adapters.
- `AppDatabase.kt`, schema JSON, DAOs/entities: Room version 37 additions.
- navigation, MainActivity share handling, tunnel settings, and Settings: small entry points for Sites and diagnostics.
- Gradle settings/catalog/app dependency and localized `app_name` resources: `catcore` module plus Cat branding/application ID.

Keep Cat-owned pure logic in `catcore` and Android adapters under `app/.../cat` so upstream functional changes can be taken without carrying a broad package rename. Re-run `:catcore:test`, app unit tests, debug assemble, and lint after each upstream integration.
