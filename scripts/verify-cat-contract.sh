#!/usr/bin/env bash
set -euo pipefail

# Run from the Android repository. A sibling/checked-out server repo is required so drift is
# impossible to miss during cross-repo integration; normal per-repo CI still validates its copy.
server_repo="${CAT_SERVER_REPO:?set CAT_SERVER_REPO to the cat-awg-server checkout}"
diff -ru --exclude='*.pyc' contracts/v1 "$server_repo/contracts/v1"
./gradlew :catcore:test --no-parallel --max-workers=1
"$server_repo/.venv/bin/pytest" -q tests/test_contracts.py tests/test_api.py
