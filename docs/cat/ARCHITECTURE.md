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
```

## Boundaries

- `catcore` has no Android or Compose dependency. It owns durable contracts, pure planning/validation, sanitization, deterministic incidents, and mocks.
- `app/cat` contains Android adapters only: DNS resolution on the active underlying `Network`, lifecycle coordination, and bridges from existing backend/network flows.
- `tunnel/VpnService.kt` consumes a synchronous route snapshot while the builder is open. It does not read Room or do DNS itself.
- Existing `TunnelCoordinator` and `Backend.bounceTunnelDevice()` remain the owners of normal tunnel start/restart behavior.

## Persistence

Room database version 37 adds `cat_domain_rules`, `cat_diagnostic_events`, and `cat_incidents`. DNS observations are bounded JSON lists inside the rule entity so history and route decisions remain co-located. Auto migration 36→37 schema is committed under `app/schemas`.

## Security and scope

- Browser history, domain names, IP history, and diagnostics stay local unless a user explicitly exports a sanitized bundle.
- The default Cat Server and AI bindings are local mocks/disabled implementations. No provider secret is embedded in the APK.
- This track does not implement a Cat Server. `CatServerClient` is an interface for Goal-B or a later paired TLS transport.
