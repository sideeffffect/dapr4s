#!/usr/bin/env bash
# Compose an app component with dapr-wasm-components' wasi-http outbound + inbound providers (0.7.5,
# pulled from OCI by wkg) using the upstream compose.sh/compose.wac, fetched at the v0.7.5 tag (pinned,
# never `main`). compose.sh pulls the providers itself (`--tag 0.7.5`, under
# ghcr.io/sideeffffect/dapr-wasm-components/<module> since v0.7.5's compose.sh).
#   scripts/wasm/compose.sh wasm/target/components/counter.wasm wasm/target/components/composed-counter.wasm
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
app="${1:-$repo/wasm/target/components/counter.wasm}"
out="${2:-$repo/wasm/target/components/composed.wasm}"
version="0.7.5"
ref="v$version"
dir="$repo/wasm/target/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --tag "$version"
wasm-tools validate "$out"
