#!/usr/bin/env bash
# Run a composed component next to a local daprd (`dapr run`; `dapr init --slim` is enough) with the
# Redis state store in scripts/wasm/components/ (Redis on localhost:26480, see e2e.sh). Ports are
# fixed so the outbound provider can reach the sidecar via DAPR_HTTP_PORT.
#   scripts/wasm/run.sh wasm/target/components/composed-counter.wasm
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
composed="${1:-$repo/wasm/target/components/composed.wasm}"
APP_ID="${APP_ID:-dapr4s-wasm}"
APP_PORT="${APP_PORT:-18087}"
DAPR_HTTP_PORT="${DAPR_HTTP_PORT:-3587}"
DAPR_GRPC_PORT="${DAPR_GRPC_PORT:-50087}"
exec dapr run --app-id "$APP_ID" --app-port "$APP_PORT" --dapr-http-port "$DAPR_HTTP_PORT" \
  --dapr-grpc-port "$DAPR_GRPC_PORT" --resources-path "$here/components" -- \
  wasmtime serve -S cli --addr "127.0.0.1:$APP_PORT" --env "DAPR_HTTP_PORT=$DAPR_HTTP_PORT" "$composed"
