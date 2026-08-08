# Connect an existing Cat Server

Open **Settings → Cat Server** in Cat AWG Tunnel.

1. Obtain the server HTTPS URL and SHA-256 fingerprint out of band. The fingerprint printed by the installer may be pasted with `SHA256:`/`SHA-256:` and colons; the app normalizes it for display and comparison.
2. Enter the URL (`https://host:port`), fingerprint and a device name.
3. Run **Health check**. The app keeps hostname verification enabled and rejects HTTP, certificate mismatches and hostname mismatches.
4. Enter the one-time bootstrap token from the Cat Server installer/operator and press **Pair**.
5. After pairing, the device token is encrypted with AES-GCM under an Android Keystore key. It is stored only in `noBackupFilesDir`; the token is not in DataStore, normal files, exports or logs.

The copy/paste/QR-compatible payload is:

```text
catpair:v1
server=https://host:8443
fingerprint=sha256:<64 hexadecimal characters>
token=<one-time bootstrap token>
```

The app also accepts `cat://pair?...` and JSON with the same three fields. Bootstrap material is used only during pairing and is not persisted. **Forget local pairing** clears the encrypted token and metadata; it does not revoke the server device, so revoke the device in the server administration path when required.

The capabilities card is authoritative: AWG3 is displayed as unavailable unless the server advertises it. Diagnostics upload is optional and never gates VPN operation. A 401/403 is presented as expired/revoked pairing, network failures remain actionable, and protocol mismatches are not retried aggressively.
