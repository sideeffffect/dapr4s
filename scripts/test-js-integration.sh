#!/usr/bin/env bash
# One-command entry point for the Scala.js integration tests (CI and developers):
#
#   1. brings up the Dapr environment + the packaged JS test server
#      (scripts/js-integration-env.sh up),
#   2. runs the dapr4s.test.integration.* munit suites on the experimental WebAssembly
#      backend (Wasm + JSPI; scripts/wasm-test.sh handles the known scala-cli cleanup bug),
#   3. always tears the environment down again, preserving the test exit code.
#
# Requirements:
#   - Node.js >= 25 first on PATH (JSPI is on by default there; checked below).
#     Locally e.g.: PATH=/tmp/node-v25.5.0-linux-x64/bin:$PATH scripts/test-js-integration.sh
#     CI installs it via setup-node.
#   - scala-cli >= 1.14 (override with SCALA_CLI=/path/to/scala-cli).
#   - Docker.
#   - The ScalablyTyped facade jars in ~/.ivy2/local (scripts/generate-st-facades.sh) and
#     `npm ci` done at the repo root (the test server loads @dapr/dapr at runtime).
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

"$ROOT/scripts/js-integration-env.sh" up || exit 1

# scala-cli runs the linked test module from a temp dir, where Node's ESM resolver cannot see
# the repo's node_modules (bare specifiers resolve relative to the MODULE's path; NODE_PATH is
# ignored for ES modules). The resolution hook retries bare specifiers against the repo root —
# see scripts/js-it/node-resolve-hook.mjs.
export DAPR4S_REPO_ROOT="$ROOT"
export NODE_OPTIONS="--import $ROOT/scripts/js-it/node-resolve-hook.mjs"

# The ScalablyTyped facades are compileOnly.dep (js-deps.scala), which leaves them off the
# classpath this Wasm `test` link uses — so add their jars back via --jar, same as the
# JsTestServer package step in js-integration-env.sh. (The plain-JS unit `test` leg links without
# them, but the Wasm `test`/`package` link does not — see scripts/st-link-jars.sh.)
mapfile -t st_link < <("$ROOT/scripts/st-link-jars.sh")

"$ROOT/scripts/wasm-test.sh" \
  --power --js --js-emit-wasm --js-module-kind es "$ROOT" \
  "${st_link[@]}" \
  --test-only 'dapr4s.test.integration.*'
code=$?
unset NODE_OPTIONS DAPR4S_REPO_ROOT

"$ROOT/scripts/js-integration-env.sh" down || true
exit "$code"
