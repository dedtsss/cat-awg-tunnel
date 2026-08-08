# Install/Connect VPS from Android

This phase implements the complete **Connect existing Cat Server** path. It does not embed a
persistent SSH terminal or generate arbitrary remote root commands.

For a user-owned VPS, use the reviewed Cat Server installer from the server repository through an
operator-controlled SSH session, run its preflight, preserve the existing SSH access/firewall, and
copy only the resulting HTTPS URL, certificate fingerprint and one-time pairing token into the app.
After pairing, normal management uses Cat Protocol v1 over HTTPS; the SSH credential is not entered
into or retained by Cat AWG Tunnel.

This is an intentional guided fallback. An Android SSH bootstrap implementation would need a
maintained permissively licensed SSH library, a reviewed/pinned installer transport, host-key
verification UI, cancellation and reliable handling of firewall changes. Adding a password/private-
key terminal without those controls would weaken the product's security boundary. Agent-only and
full server installation remain a server-side deployment task; the Android pairing UX is independent
of that choice.
