# Cat AWG Tunnel architecture

Cat AWG Tunnel is a controlled product fork of WG Tunnel, not a rewrite. Kotlin namespaces remain upstream-compatible; only the installable `applicationId` and visible product name are Cat-specific. That keeps upstream merges focused on small Cat-owned seams.

```text
Compose UI / share intent
        │
        ├── DomainSitesViewModel ────── DomainRoutingCoordinator
        │                                      │
Room DomainRuleRepository ───────────────────────┤
AndroidDomainResolver ── underlying Android network│
AndroidDomainRouteProvider ── VpnService.Builder.excludeRoute()
        │                                      │
        └── diagnostics event ── ClientDiagnosticRecorder ── Room store/export

catcore (pure Kotlin, portable)
  routing · diagnostics · configurator · Cat Protocol v1 · optional AI boundary

Cat Server Android boundary
  Compose pairing/configurator/diagnostics
        │
        ├── AndroidCatServerClient ── KtorCatServerClient ── pinned HTTPS Cat Server
        ├── Android Keystore credential store
        └── WorkManager diagnostics sync (never on the VPN critical path)
```

## Boundaries

- `catcore` has no Android or Compose dependency. It owns durable contracts, pure planning/validation, sanitization, deterministic incidents, and mocks.
- `app/cat` contains Android adapters only: DNS resolution on the active underlying `Network`, lifecycle coordination, and bridges from existing backend/network flows.
- `tunnel/VpnService.kt` consumes a synchronous route snapshot while the builder is open. It does not read Room or do DNS itself.
- Existing `TunnelCoordinator` and `Backend.bounceTunnelDevice()` remain the owners of normal tunnel start/restart behavior.

## Persistence

Room database version 37 adds `cat_domain_rules`, `cat_diagnostic_events`, and `cat_incidents`. DNS observations are bounded JSON lists inside the rule entity so history and route decisions remain co-located. Auto migration 36→37 schema is committed under `app/schemas`.

Cat Server endpoint metadata, capabilities, sync state, public configurator candidates, and change
history use DataStore. The device bearer token is AES-GCM encrypted under an Android Keystore key
in `noBackupFilesDir`; it is deliberately absent from DataStore and Android backup/transfer.

## Security and scope

- Browser history, domain names, IP history, and diagnostics stay local unless a user explicitly exports a sanitized bundle.
- Production DI binds `CatServerClient` to `AndroidCatServerClient`, which creates the real pinned
  `KtorCatServerClient` only after an HTTPS endpoint and verified certificate fingerprint are set.
  `InMemoryCatServerClient` remains a test boundary only.
- Cat Server AI is capability-gated and server-backed; no provider key or automatic apply path is
  embedded in the APK. Standalone/offline diagnostics and configurator validation remain usable.
