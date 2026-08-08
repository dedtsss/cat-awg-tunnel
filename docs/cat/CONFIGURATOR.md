# Portable WG/AWG configurator

The reusable implementation is in `catcore/configurator`, not Compose:

- `AwgConfigSchema`, `AwgConfigParser`, `AwgConfigValidator`, `AwgConfigGenerator`;
- `AwgCompatibilityEngine` and server/client capability model;
- `ConfigProfile`, `PublicConfigProfile`, `ConfigurationChange`, `DiagnosticAction`, `ConfigurationResult`, `Recommendation`;
- repository, recommendation-engine, and test-engine interfaces plus a testable in-memory boundary.

The existing Android configuration editor calls the portable validator before it saves a non-global tunnel candidate. It does not invent parameter values. Existing upstream parsing/apply behavior remains authoritative for application of the configuration.

AWG2 is the concrete target. Its validator uses the ranges already exposed by upstream's AWG editor and checks required fields, CIDRs, DNS syntax, endpoint/port, keepalive, MTU, Awg parameter ranges, and `Jmin ≤ Jmax`. AWG3 is modeled but default client capability is `false`; validation explicitly reports that the bundled backend does not claim support.

Candidates are never auto-applied. Public profiles strip `PrivateKey`, `PresharedKey`, passwords, tokens, and secret-like fields; secret-bearing delivery must use a separate protected path later.

Versioned examples are under [`contracts/v1`](../../contracts/v1/), with additional notes in [configurator/README.md](configurator/README.md).
