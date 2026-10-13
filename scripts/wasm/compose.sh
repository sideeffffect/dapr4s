#!/usr/bin/env bash
# Compose an app component with dapr-wasm-components' wasi-http outbound + inbound providers (0.7.1,
# pulled from OCI by wkg) using the upstream compose.sh/compose.wac, fetched at the v0.7.1 tag (pinned,
# never `main`).
#   scripts/wasm/compose.sh wasm/target/components/counter.wasm wasm/target/components/composed-counter.wasm
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
app="${1:-$repo/wasm/target/components/counter.wasm}"
out="${2:-$repo/wasm/target/components/composed.wasm}"
ref="v0.7.1"
dir="$repo/wasm/target/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
# Upstream compose.sh exits 0 on success since v0.7.1 (at v0.7.0 its EXIT trap made it exit 1 even on
# success). Its exit code is trusted now; the output is still validated.
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --tag 0.7.1
wasm-tools validate "$out"
