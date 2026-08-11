# Cat AWG test channel signing

The installable `standaloneDebug` Cat test channel uses a repository secret
keystore when built by `.github/workflows/cat-debug-apk.yml`. The keystore and
passwords are never stored in Git; the workflow fails closed if any of these
secrets is missing:

- `CAT_AWG_TEST_KEYSTORE` — base64-encoded JKS;
- `CAT_AWG_TEST_STORE_PASSWORD`;
- `CAT_AWG_TEST_KEY_ALIAS`;
- `CAT_AWG_TEST_KEY_PASSWORD`.

The debug application id remains `com.dedtsss.catawgtunnel.debug`, so the
channel can update its existing installation without changing the package or
losing app data. The workflow builds two sequential APKs using the same
keystore, verifies the certificate fingerprint is identical, checks the second
`versionCode` is greater, installs A, writes a private app-data marker, then
updates to B and verifies the marker survived. It also runs the production
instrumentation startup smoke against B.

The base code is `50202`. CI allocates two codes per workflow run as
`50202 + 2 * github.run_number` and the following code. This makes successive
test-channel workflow runs monotonic without committing a mutable generated
number.

If the old one-off debug APK is still installed and its debug key cannot be
recovered, uninstall it once before installing the first permanently signed
test-channel build. Further builds must update in place.
