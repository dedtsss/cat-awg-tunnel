#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
default_file="$(mktemp)"
russian_file="$(mktemp)"
trap 'rm -f "$default_file" "$russian_file"' EXIT

# Every UI string in the default catalog must have a Russian value. IDs, URLs, addresses and
# protocol keys marked translatable=false are intentionally excluded.
rg '<string name="' "$root_dir/app/src/main/res/values/strings.xml" \
  | rg -v 'translatable="false"' \
  | sed -E 's/.*name="([^"]+)".*/\1/' \
  | sort -u > "$default_file"
rg -o 'name="[^"]+"' "$root_dir/app/src/main/res/values-ru/strings.xml" \
  | sed 's/name="//;s/"//' \
  | sort -u > "$russian_file"

missing="$(comm -23 "$default_file" "$russian_file")"
if [[ -n "$missing" ]]; then
  printf '%s\n%s\n' 'Missing Russian strings:' "$missing" >&2
  exit 1
fi

printf '%s\n' 'Russian localization coverage: complete for localizable default strings.'
