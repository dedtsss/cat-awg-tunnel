# Configurator contract notes

[`contracts/v1/awg-config-schema.json`](../../../contracts/v1/awg-config-schema.json) is a portable discovery fixture, not a guarantee that every listed field is supported by every engine. Clients must combine profile protocol requirements with `AwgCapabilities`; AWG3 remains capability-gated.

The parser is intentionally a small deterministic INI contract parser. Android continues to apply configurations through the existing upstream parser/backend, so a new portable layer cannot silently broaden the runtime configuration surface.
