#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' 'Usage: cat-pairing-link.sh --server https://host[:port] --fingerprint SHA-256:... --token one-time-token [--qr-output file.png]'
}

server=''
fingerprint=''
token=''
qr_output=''

while (($#)); do
  case "$1" in
    --server) server="${2-}"; shift 2 ;;
    --fingerprint) fingerprint="${2-}"; shift 2 ;;
    --token) token="${2-}"; shift 2 ;;
    --qr-output) qr_output="${2-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

[[ "$server" == https://* ]] || { printf '%s\n' 'Server must use https://.' >&2; exit 2; }
[[ -n "$fingerprint" ]] || { printf '%s\n' 'A certificate fingerprint is required.' >&2; exit 2; }
[[ -n "$token" ]] || { printf '%s\n' 'A one-time token is required.' >&2; exit 2; }

command -v python3 >/dev/null || { printf '%s\n' 'python3 is required for URI encoding.' >&2; exit 127; }
deep_link="$(python3 - "$server" "$fingerprint" "$token" <<'PY'
import sys
from urllib.parse import quote

server, fingerprint, token = sys.argv[1:]
print(
    'catpair:v1?server={}&fingerprint={}&token={}'.format(
        quote(server, safe=''), quote(fingerprint, safe=''), quote(token, safe='')
    )
)
PY
)"

printf '%s\n' "$deep_link"

if [[ -n "$qr_output" ]]; then
  command -v qrencode >/dev/null || {
    printf '%s\n' 'qrencode is required for --qr-output.' >&2
    exit 127
  }
  qrencode --type PNG --output "$qr_output" "$deep_link"
fi
