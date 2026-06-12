#!/usr/bin/env bash
# Render the canonical Dapr component set (scripts/it/components/*.yaml) for one topology by
# substituting the single environment-specific value, ${DAPR4S_IT_REDIS_HOST}, into a fresh
# output directory. This is the ONE source of truth both integration harnesses consume:
#
#   - the JVM testcontainers harness renders with redis:6379 (shared Docker network) and feeds
#     each rendered file to io.dapr.testcontainers.DaprContainer.withComponent(Path);
#   - the JS (Wasm+JSPI) harness renders with localhost:6391 (host network) and mounts the
#     output dir into daprd via --resources-path.
#
# Usage: scripts/it/render-components.sh <redis-host> <out-dir>
#   <redis-host>  value for redisHost, e.g. "redis:6379" or "localhost:6391"
#   <out-dir>     directory to (re)create with the rendered *.yaml
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <redis-host> <out-dir>" >&2
  exit 2
fi
redis_host="$1"
out_dir="$2"
src_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/components" && pwd)"

rm -rf "$out_dir"
mkdir -p "$out_dir"

# Substitute ONLY ${DAPR4S_IT_REDIS_HOST} (export + envsubst with an explicit var list, so any
# other ${...} in the YAML is left untouched).
export DAPR4S_IT_REDIS_HOST="$redis_host"
for f in "$src_dir"/*.yaml; do
  envsubst '${DAPR4S_IT_REDIS_HOST}' < "$f" > "$out_dir/$(basename "$f")"
done
