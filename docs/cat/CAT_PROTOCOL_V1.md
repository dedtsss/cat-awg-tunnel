# Cat Protocol v1

This Android track defines a client-facing contract only; it does not implement Cat Server.

```text
prefix: /api/v1
schemaVersion: cat.v1
timestamps: RFC3339 UTC
IDs: opaque UUID/string values
post-pairing MVP auth: token over TLS
future auth: device credentials / mTLS-compatible boundary
```

| Method | Endpoint | Client boundary |
| --- | --- | --- |
| GET | `/api/v1/health` | `health()` |
| GET | `/api/v1/capabilities` | `capabilities()` |
| POST | `/api/v1/diagnostics/events` | `postDiagnosticEvents()` |
| GET | `/api/v1/incidents?from=&to=` | `incidents()` |
| GET | `/api/v1/diagnostics/bundle?from=&to=` | `diagnosticBundle()` |
| POST | `/api/v1/config/validate` | `validateConfig()` |
| POST | `/api/v1/ai/chat` | `aiChat()`; optional capability |

`CatServerClient` and `InMemoryCatServerClient` live in `catcore/protocol`. `CatAiProvider` defaults to disabled, so routing, diagnostics, and validation work with no server/AI credentials. Generic diagnostic and public profile payloads never contain private keys, PSKs, passwords, provider secrets, or secret-bearing configuration.

Fixtures shared with a later server implementation are under [`contracts/v1`](../../contracts/v1/): server capabilities, diagnostic event, incident, public profile, and AWG schema. JVM tests decode each typed fixture and exercise the mock boundary.
