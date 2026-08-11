# Physical-device validation checklist

Use a disposable Android device or emulator and a disposable Cat Server/VPS. Record the app commit
SHA shown under Settings → About.

1. Install `cat-awg-tunnel-debug-<sha>.apk`; confirm the About screen shows version, flavor and git SHA.
2. In Settings → Cat Server, enter an HTTPS URL and verify the fingerprint out of band. Confirm HTTP,
   wrong fingerprint and wrong hostname are rejected.
3. Run health check, paste a `catpair:v1` payload, pair a named device, refresh capabilities and
   confirm AWG3 is not claimed when the server says unavailable.
4. Force-stop/restart the app; confirm the pairing remains usable without a token in normal settings
   or diagnostic export. Use Forget local pairing and confirm the local token is no longer usable.
5. Start/stop a normal WireGuard/AWG tunnel and exercise Auto Tunnel, split tunneling, kill switch,
   Domain Routing and Share Target. A Cat diagnostics outage must not stop the VPN.
6. Create local network/tunnel/DNS events, open Diagnostics, run sync and verify pending/success
   state. Fetch server incidents and a combined 15-minute/1-hour bundle.
7. Ask the AI Assistant only when the server advertises `aiGateway`; verify candidate-only wording
   and that no setting is applied. Disable the capability and verify deterministic diagnostics remain.
8. Import and export an AWG2 profile in AWG Configurator, validate it, save a named candidate,
   compare two explicitly selected public profiles, read a parameter explanation, and confirm that
   deterministic diagnostic recommendations never apply a setting. Apply the candidate to one
   selected tunnel only after the confirmation dialog; if active, verify the normal reconnect.
   Inspect the public-only experiment history and before/after metrics. Confirm a profile with
   PrivateKey/PresharedKey cannot be sent as a public request or stored in history.
9. With the tunnel connected, verify one collapsed foreground notification shows a health symbol and
   both RX/TX speeds without the words “Connected” or “Stable”; expand it to see status/health.
   Add a suffix and an exact domain rule, refresh A/AAAA, use browser Share → Cat AWG Tunnel, and
   check a direct-rule site plus domain diagnostics/shared-IP wording.
10. Test offline/server-down behavior: local history/export still works, retries remain bounded, and
   the UI gives an actionable error.

Physical VPN handshakes, browser traffic and real VPS firewall behavior must be marked **not run**
unless they were actually exercised on the device/server.
