# AWG Configurator v1

The user-facing module is available at **Settings → AWG Configurator**. It provides:

- paste/import of an existing WireGuard/AWG INI profile;
- explicit WireGuard/AWG2 selection and an honest AWG3-unavailable state;
- deterministic local validation with field-level issues;
- public/redacted server validation through `/api/v1/config/validate`;
- named public candidate history, explicit two-profile comparison and a bundled Russian parameter
  guide (purpose, range, increase/decrease effect, risk and only documented notes);
- local deterministic diagnostic recommendations, experiment history, and a descriptive
  before/after reliability view;
- explicit application to a selected local tunnel. The active target uses the normal single-tunnel
  reconnect lifecycle only after the confirmation dialog.

Only the public profile is persisted in DataStore. PrivateKey, PresharedKey, passwords, tokens and
authorization-like fields are rejected at the store/client boundary. The raw profile is held in the
screen state for local parsing and is never sent to the generic server validation endpoint. Saving a
candidate records a configuration-history entry; it does not apply a networking change.

The reusable implementation is in `catcore/configurator`, not Compose:

- `AwgConfigSchema`, `AwgConfigParser`, `AwgConfigValidator`, `AwgConfigGenerator`;
- `AwgCompatibilityEngine` and server/client capability model;
- `ConfigProfile`, `PublicConfigProfile`, `ConfigurationChange`, `DiagnosticAction`, `ConfigurationResult`, `Recommendation`;
- repository, recommendation-engine, and test-engine interfaces plus a testable in-memory boundary.

The existing Android configuration editor calls the portable validator before it saves a non-global tunnel candidate. It does not invent parameter values. Existing upstream parsing/apply behavior remains authoritative for application of the configuration.

AWG2 is the concrete target through `ProtocolConfigurator` → `Awg2ProtocolConfigurator`. Its
validator checks syntax, duplicate fields/sections, required fields, CIDRs, DNS syntax,
endpoint/port, MTU, AWG integer/type limits, uint32/range `H1`–`H4`, and `Jmin ≤ Jmax` when junk
packets are enabled. Unknown fields and unusual-but-legal combinations are warnings or info, not
made-up blocking errors. AWG3 is modeled but has no implementation and default client capability
is `false`; validation explicitly reports that the bundled backend does not claim support.

Candidates are never auto-applied. The configurator does not call an external AI provider. Its
`ConfigurationAdvisor` boundary currently has only `DeterministicConfigurationAdvisor`, which sees
sanitized event codes, incidents, public parameters, and a minimal local network-evidence summary.
It always returns candidate advice with `requiresUserConfirmation=true`.

`ConfigurationExperiment` stores timestamp, public-only SHA-256 fingerprint, public parameter
diff, sanitized network context, before/after diagnostic snapshots, result, and user decision. It
never contains a private key, preshared key, token, or raw configuration. This is the future API
boundary for an optional compatible/OpenAI advisor; adding one must preserve this sanitized input
contract.

Applying a candidate writes only the selected Android tunnel. There is no arbitrary remote shell
and no server apply endpoint. Cat Server validation remains public-only; real server-side AWG
configuration management would require fixed operations with validation, backup, transaction and
rollback before Android may expose it. The before/after card is descriptive only and reports
insufficient data rather than claiming causality on small samples.

Versioned examples are under [`contracts/v1`](../../contracts/v1/), with additional notes in [configurator/README.md](configurator/README.md).
