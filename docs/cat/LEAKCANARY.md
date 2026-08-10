# LeakCanary and `VpnService` retention

The normal Cat AWG Tunnel test-channel APK does **not** include LeakCanary. Leak reports are a
developer diagnostic, not an end-user notification: the dependency is scoped to the explicit
`standaloneDiagnostic` build only. This keeps the permanently signed `standaloneDebug` APK quiet
while retaining a reproducible investigation build for maintainers.

## VpnService finding disposition

The reported retained path ends at Android's `android.net.VpnService$Callback` after service
teardown. Code audit found no application-owned static or singleton reference from that callback
back to a destroyed service:

- `ServiceManager` owns a nullable `StateFlow<VpnService?>` and clears it during teardown.
- `ProxyBackend` loses its socket protector on the same path.
- the only process-long always-on callback belongs to `WireGuardAutoTunnel` (the application), not
  to a `VpnService` instance.
- `VpnService` closes TUN/HEV descriptors, cancels its job/scope and clears the manager in
  `onDestroy`.

The teardown is additionally identity-guarded: a late `onDestroy` from an old Android service can
no longer clear a newly registered `VpnService` or its socket protector. The remaining callback
retention is therefore classified as **framework/binder-owned unless a diagnostic build shows an
application-owned path beyond this boundary**. It is documented, not blanket-suppressed.

## Maintainer procedure

1. Build and install `standaloneDiagnostic`; do not use it as the user-facing test artifact.
2. Start and stop a VPN once, wait for LeakCanary analysis, and inspect the full retain chain.
3. Treat any path through `ServiceManager`, `ProxyBackend`, a coroutine/job, a static callback, or
   a UI/context object as an application regression and fix it before closing the finding.
4. If the chain ends at Android framework callback/binder state only, record Android version,
   device model and heap trace in the issue without disabling security or leak checks.

No TLS/bootstrap policy, certificate pinning, hostname verification, or service lifetime safety is
weakened by this notification change.
