# Client diagnostics and incidents

`ClientDiagnosticRecorder` stores sanitized structured events in Room for roughly 48 hours. Android bridges feed existing backend events/status and `networkmonitor` state into categories such as `NETWORK`, `TUNNEL`, `DNS`, and `ROUTE`. Domain refresh/rebuild activity is also recorded.

The deterministic `IncidentDetector` is deliberately conservative:

- an underlying network interruption is INFO and can be resolved when a later network-available event appears;
- repeated underlying-network loss or reconnect failure produces WARNING;
- recovery exhaustion plus an available network is CRITICAL;
- repeated DNS failures and IP-family mismatch are classified independently;
- an old WireGuard handshake alone is not evidence of failure.

Open incidents are closed once their classification no longer has current evidence; the 48-hour retained store is consulted so closure is not lost when the 15-minute detector window rolls over.

## Export

Settings → Client diagnostics lets the user export 15 minutes, 1 hour, 6 hours, or 24 hours. The resulting ZIP has the server-merge-compatible layout:

```text
manifest.json
client/events.jsonl
client/logs.txt
client/network.jsonl
client/incidents.json
```

The sanitizer removes known private-key, preshared-key, password, token, API-key, and secret assignments/fields before persistence and before export. Export is explicit and local; no background upload exists.
