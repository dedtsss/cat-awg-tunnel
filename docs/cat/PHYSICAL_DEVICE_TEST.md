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
8. Import a profile in AWG Configurator, validate, save a named candidate, compare two public profiles,
   remote-validate a redacted profile, and inspect before/after metrics. Confirm a profile with
   PrivateKey/PresharedKey cannot be sent as a public request.
9. Test offline/server-down behavior: local history/export still works, retries remain bounded, and
   the UI gives an actionable error.

Physical VPN handshakes, browser traffic and real VPS firewall behavior must be marked **not run**
unless they were actually exercised on the device/server.
