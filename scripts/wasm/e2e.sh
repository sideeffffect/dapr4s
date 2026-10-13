#!/usr/bin/env bash
# End-to-end test of the `wasm` platform prototype (Phase 0). Builds the two test components
# (test/wasm/counter-raw: raw WIT bindings; test/wasm/counter: dapr4s API), then for each:
#   1. W1 authority check: the component imports exactly state-read.get + state.save (and only the
#      types of types / invocation / configuration-read), nothing else;
#   2. composes it with the dapr-wasm-components 0.7.1 wasi-http providers (compose.sh);
#   3. runs it in `wasmtime serve` next to daprd (`dapr run`, run.sh) with a Redis state store;
#   4. invokes `counter` twice through the sidecar and expects 1, then 2.
# The dapr4s app additionally checks the error mapping into dapr4s's DaprException family:
#   missing-store -> ComponentNotFoundException(State, "no-such-store")
#   stale-etag    -> Some(ETagMismatchException), and the stale write must not land (counter -> 3).
#
# Needs: Docker (Redis on localhost:26480), the dapr CLI + daprd (`dapr init --slim`), wasmtime,
# wasm-tools, wkg, wac, wit-bindgen-scala, sbt, and the patched linker (build-patched-linker.sh).
# SKIP_BUILD=1 reuses wasm/target/components/*.wasm. SBT_ARGS is passed to sbt, e.g.
# SBT_ARGS=-Ddapr4s.wasm.coreRelease=3.0.0 links against the published dapr4s_sjs1_3 (see wasm/build.sbt).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
components="$repo/wasm/target/components"
logs="$repo/wasm/target/e2e"
redis=dapr4s-wasm-redis
export APP_ID=dapr4s-wasm APP_PORT=18087 DAPR_HTTP_PORT=3587 DAPR_GRPC_PORT=50087
mkdir -p "$logs"

started_redis=0
stop_app() {
  dapr stop --app-id "$APP_ID" >/dev/null 2>&1 || true
  pkill -f "^wasmtime serve -S cli --addr 127.0.0.1:$APP_PORT" 2>/dev/null || true
  sleep 2
}
cleanup() {
  stop_app
  [[ "$started_redis" == 1 ]] && docker rm -f "$redis" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "e2e: FAILED: $*" >&2
  for f in "$logs"/*.log; do [[ -f "$f" ]] && { echo "--- $f (tail)"; tail -40 "$f"; } >&2; done
  exit 1
}

if ! docker ps --format '{{.Names}}' | grep -qx "$redis"; then
  docker run -d --rm --name "$redis" -p 26480:6379 redis:7-alpine >/dev/null
  started_redis=1
fi

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  (cd "$repo/wasm" && sbt -batch ${SBT_ARGS:-} counterRaw/componentWasm counter/componentWasm)
fi

expected_imports=$'configuration-read: \ninvocation: \nstate: save\nstate-read: get\ntypes: '

invoke() { dapr invoke --app-id "$APP_ID" --method "$1" --verb POST 2>&1 | head -1; }

for v in counterRaw counter; do
  echo "== $v"
  got_imports="$("$here/imported-functions.py" "$components/$v.wasm")"
  [[ "$got_imports" == "$expected_imports" ]] ||
    fail "$v: imported functions differ from state-read.get + state.save:"$'\n'"$got_imports"
  echo "$v: imports state-read.get + state.save only (W1)"

  "$here/compose.sh" "$components/$v.wasm" "$components/composed-$v.wasm" >"$logs/compose-$v.log" 2>&1 ||
    fail "$v: compose"
  docker exec "$redis" redis-cli DEL "$APP_ID||counter" >/dev/null
  "$here/run.sh" "$components/composed-$v.wasm" >"$logs/run-$v.log" 2>&1 &
  up=0
  for _ in $(seq 1 90); do
    grep -q "up and running" "$logs/run-$v.log" && { up=1; break; }
    sleep 1
  done
  [[ "$up" == 1 ]] || fail "$v: sidecar + component did not come up"
  sleep 1

  got="$(invoke counter) $(invoke counter)"
  echo "$v: counter -> $got"
  [[ "$got" == "1 2" ]] || fail "$v: counter returned '$got', expected '1 2'"

  if [[ "$v" == counter ]]; then
    missing="$(invoke missing-store)"
    echo "$v: missing-store -> $missing"
    [[ "$missing" == '"ComponentNotFoundException: State no-such-store"' ]] ||
      fail "$v: missing-store returned '$missing'"
    stale="$(invoke stale-etag)"
    echo "$v: stale-etag -> $stale"
    [[ "$stale" == "\"ETagMismatchException: ETag mismatch for key 'counter' (provided: 999999)\"" ]] ||
      fail "$v: stale-etag returned '$stale'"
    third="$(invoke counter)"
    [[ "$third" == 3 ]] || fail "$v: counter after the stale write returned '$third', expected 3"
    echo "$v: the stale write did not land (counter -> 3)"
  fi
  stop_app
done
echo "e2e: OK"
