# Canonical Cat Protocol v1

Cat AWG Tunnel and Cat Server share the byte-identical machine-checkable catalog in `contracts/v1/`. Before cross-repo delivery run `CAT_SERVER_REPO=/path/to/cat-awg-server scripts/verify-cat-contract.sh`; it performs the byte drift check, Android JVM deserialization suite, and server live-API contract suite.

The protocol uses `/api/v1`, camelCase JSON, opaque string IDs, `schemaVersion: "cat.v1"`, and RFC3339 timestamps with a timezone/UTC normalization. Android accepts future unknown response fields deliberately; server models reject unknown request fields deliberately.

| Boundary | Android implementation |
| --- | --- |
| Health | `CatHealth(schemaVersion, status, agentVersion, at)` |
| Pairing | `KtorCatServerClient.pair()` implements `/pairing/start` and `/pairing/complete` |
| Auth | later API calls send a device Bearer token over certificate-fingerprint-pinned HTTPS |
| Capabilities | `ServerFeatures.routingBackend` is nullable and future fields are safely ignored |
| Diagnostics | `postDiagnosticEvents()` sends the canonical batch envelope; Android sends only `CLIENT` events and string `tunnelId` |
| Incidents | `IncidentsResponse` keeps the versioned server envelope |
| Config | `ConfigValidationRequest` sends a sanitized `PublicConfigProfile`, never raw config/private keys/PSKs |
| Reliability | `metricsCompare()` reads descriptive before/after metrics from `/metrics/compare` |
| AI | optional `CatAiChatRequest/Response`; response is candidate-only and `applied=false` |

`CatBootstrapPayload`/`CatBootstrapParser` provide the canonical `catpair:v1` deep-link/QR
envelope plus multiline, JSON and legacy `cat://pair` migration input. `AndroidCatServerClient` is the production adapter and
`AndroidKeystoreCatServerCredentialStore` stores only the encrypted bearer credential. Initial
bootstrap accepts only out-of-band verified certificate fingerprint material; hostname checking
stays enabled and there is no trust-all path.

Physical-device and real-VPS validation remain separate deployment checks. The JVM suite proves models and cross-contract fixtures; server tests exercise the local live API and bundle sanitizer.
