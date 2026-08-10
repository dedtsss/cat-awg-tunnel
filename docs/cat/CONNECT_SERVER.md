# Connect an existing Cat Server

Open **Settings → Cat Server** in Cat AWG Tunnel.

1. Prefer the installer/server-panel QR code or its copyable `catpair:v1` link. Open it in Android or choose **Settings → Cat Server → Scan QR code**.
2. Confirm the server and run **Verify server**. The app keeps hostname verification enabled and rejects HTTP, certificate mismatches and hostname mismatches.
3. Name the phone and choose **Connect this phone**.
4. Use **Manual / advanced settings** only when the QR/link is unavailable. The fingerprint printed by the installer may be pasted with `SHA256:`/`SHA-256:` and colons; the app normalizes it for display and comparison.
5. After pairing, the device token is encrypted with AES-GCM under an Android Keystore key. It is stored only in `noBackupFilesDir`; the token is not in DataStore, normal files, exports or logs.

The copy/paste/QR-compatible payload is:

```text
catpair:v1
server=https://host:8443
fingerprint=sha256:<64 hexadecimal characters>
token=<one-time bootstrap token>
```

The canonical one-line link is `catpair:v1?server=...&fingerprint=...&token=...`; its exact text is also the QR payload. Details and a server-panel response contract are in [PAIRING_QR_FORMAT.md](PAIRING_QR_FORMAT.md). The app also accepts legacy `cat://pair?...` and JSON with the same three fields. Bootstrap material is used only during pairing and is not persisted. **Forget local pairing** clears the encrypted token and metadata; it does not revoke the server device, so revoke the device in the server administration path when required.

The capabilities card is authoritative: AWG3 is displayed as unavailable unless the server advertises it. Diagnostics upload is optional and never gates VPN operation. A 401/403 is presented as expired/revoked pairing, network failures remain actionable, and protocol mismatches are not retried aggressively.
