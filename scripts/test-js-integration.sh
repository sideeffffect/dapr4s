#!/usr/bin/env bash
# One-command entry point for the Scala.js integration tests (CI and developers).
#
# The Dapr sidecar (with Redis-backed components, placement and scheduler) is started from INSIDE
# the test runtime by @dapr/testcontainer-node — the exact twin of how the JVM suites use
# io.dapr:testcontainers-dapr (see test/js/integration/DaprJsItFixtures.scala). There is no
# separate environment to bring up or tear down any more: this script just runs the munit suites
# on the experimental WebAssembly backend (Wasm + JSPI), and testcontainers manages every
# container (and reaps them via its Ryuk container at process exit).
#
# Requirements:
#   - Node.js >= 25 first on PATH (JSPI is on by default there; checked below).
#     Locally e.g.: PATH=/tmp/node-v25.5.0-linux-x64/bin:$PATH scripts/test-js-integration.sh
#     CI installs it via setup-node.
#   - scala-cli >= 1.14 (override with SCALA_CLI=/path/to/scala-cli).
#   - Docker (testcontainers talks to it directly).
#   - The ScalablyTyped facade jars in ~/.ivy2/local (scripts/generate-st-facades.sh) and
#     `npm ci` done at the repo root (the test runtime loads @dapr/dapr, testcontainers and
#     @dapr/testcontainer-node).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# -- Node >= 25 check (JSPI by default; Node 23/24 would need --experimental-wasm-jspi, which
#    scala-cli's runner does not pass — see wiki/scala-js/scala-js-async-jspi-wasm.md).
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node not found on PATH; the Wasm+JSPI tests need Node >= 25." >&2
  exit 1
fi
node_version="$(node --version)"          # e.g. v25.5.0
node_major="${node_version#v}"; node_major="${node_major%%.*}"
if [ "$node_major" -lt 25 ]; then
  echo "ERROR: Node >= 25 required for JSPI (found $node_version)." >&2
  echo "       Locally: PATH=/tmp/node-v25.5.0-linux-x64/bin:\$PATH $0" >&2
  exit 1
fi

# -- Docker check (testcontainers needs a working daemon).
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not available; the integration suites start a Dapr sidecar via testcontainers." >&2
  exit 1
fi

# scala-cli runs the linked test module from a temp dir, where Node's ESM resolver cannot see
# the repo's node_modules (bare specifiers resolve relative to the MODULE's path; NODE_PATH is
# ignored for ES modules). The resolution hook retries bare specifiers against the repo root, and
# adds the ".js" extension the ScalablyTyped deep submodule imports (testcontainers,
# @dapr/testcontainer-node) need under ESM — see scripts/js-it/node-resolve-hook.mjs.
export DAPR4S_REPO_ROOT="$ROOT"
export NODE_OPTIONS="--import $ROOT/scripts/js-it/node-resolve-hook.mjs"

# The ScalablyTyped facades are compileOnly.dep (js-deps.scala), which leaves the three MAIN roots
# off the classpath this Wasm `test` link uses — add their jars back via --jar. (The testcontainers
# TEST facades in js-test-deps.test.scala are plain `dep`s, so their closure is already on the test
# link classpath; st-link-jars.sh only needs to cover the compileOnly main roots.)
mapfile -t st_link < <("$ROOT/scripts/st-link-jars.sh")

"$ROOT/scripts/wasm-test.sh" \
  --power --js --js-emit-wasm --js-module-kind es "$ROOT" \
  "${st_link[@]}" \
  --test-only 'dapr4s.test.integration.*'
code=$?
unset NODE_OPTIONS DAPR4S_REPO_ROOT
exit "$code"
