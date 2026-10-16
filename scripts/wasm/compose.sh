#!/usr/bin/env bash
# Compose an app component with dapr-wasm-components' wasi-http outbound + inbound providers (0.7.2,
# pulled from OCI by wkg) using the upstream compose.sh/compose.wac, fetched at the v0.7.2 tag (pinned,
# never `main`).
#   scripts/wasm/compose.sh wasm/target/components/counter.wasm wasm/target/components/composed-counter.wasm
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
app="${1:-$repo/wasm/target/components/counter.wasm}"
out="${2:-$repo/wasm/target/components/composed.wasm}"
ref="v0.7.2"
dir="$repo/wasm/target/dapr-wasm-components-$ref"
mkdir -p "$dir"
for f in compose.sh compose.wac; do
  [[ -s "$dir/$f" ]] || curl -fsSL --retry 3 -o "$dir/$f" \
    "https://raw.githubusercontent.com/sideeffffect/dapr-wasm-components/$ref/$f"
done
chmod +x "$dir/compose.sh"
# The providers are pulled HERE and passed as explicit paths: dapr-wasm-components v0.7.4 moved every
# module to one OCI namespace (ghcr.io/sideeffffect/dapr-wasm-components/<module>; the old flat
# dapr-wasm-components-<module> packages are gone from ghcr, versions copied byte for byte), but
# upstream compose.sh (v0.7.2 up to main) still pulls the old flat names.
for d in outbound inbound; do
  f="$dir/wasi-http-$d-0.7.2.wasm"
  [[ -s "$f" ]] || wkg oci pull "ghcr.io/sideeffffect/dapr-wasm-components/wasi-http-$d:0.7.2" -o "$f"
done
rm -f "$out"
"$dir/compose.sh" "$app" -o "$out" --outbound "$dir/wasi-http-outbound-0.7.2.wasm" \
  --inbound "$dir/wasi-http-inbound-0.7.2.wasm"
wasm-tools validate "$out"
