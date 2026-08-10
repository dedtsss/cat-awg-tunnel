# Cat Server pairing QR and link format

The canonical cross-device pairing envelope is a single `catpair:v1` URI. Put the exact URI into a
QR code and expose the same string beside it as a copyable deep link:

```text
catpair:v1?server=https%3A%2F%2Fcat.example%3A8443&fingerprint=SHA-256%3A12%3A...&token=one-time-token
```

`server`, `fingerprint`, and `token` are required exactly once. Values are percent-encoded. The
token is a one-time bootstrap token, never a device credential. Android normalizes the URL and
fingerprint, keeps hostname verification and certificate pinning enabled, and stores the resulting
device credential only through Android Keystore encryption.

For terminals and server installers, use:

```bash
scripts/cat-pairing-link.sh \
  --server https://cat.example:8443 \
  --fingerprint 'SHA-256:12:…' \
  --token "$ONE_TIME_TOKEN" \
  --qr-output cat-pairing.png
```

The helper prints the copyable URI and optionally writes a QR image through `qrencode`. It never
persists the token itself. A future web/server panel needs only this response shape:

```json
{
  "schemaVersion": "catpair.v1",
  "deepLink": "catpair:v1?...",
  "qrPayload": "catpair:v1?...",
  "expiresAt": "RFC3339 timestamp"
}
```

`deepLink` and `qrPayload` intentionally have the same value, so no mobile protocol change is
needed when the panel gains a **Connect phone** button. Android keeps accepting legacy
`cat://pair?...` and multiline `catpair:v1` input only for migration; new server output must use
the one-line `catpair:v1` URI.
