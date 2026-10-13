#!/usr/bin/env bash
# End-to-end test of the `wasm` platform. Builds the test components, then for each:
#   1. checks the WIT functions it imports (W1 authority: an app imports only what its code reaches);
#   2. composes it with the dapr-wasm-components 0.7.1 wasi-http providers (compose.sh);
#   3. runs it in `wasmtime serve` next to daprd (`dapr run`, run.sh) and calls it through the sidecar.
# Components:
#   counterRaw   (test/wasm/counter-raw: bindings + glue, no wall)   counter -> 1, then 2
#   counter      (test/wasm/counter: dapr4s's API)                    counter -> 1, then 2; error mapping:
#                missing-store -> ComponentNotFoundException(State, "no-such-store"); stale-etag ->
#                ETagMismatchException and the stale write does not land (counter -> 3)
#   importsProbe (test/wasm/imports-probe): the four imports with > 16 flat params, which only the
#                patched linker (dapr4s-wasm-linker) lowers: it must import exactly them; `converse` must come
#                back with daprd's error naming the very component it sent (the params reached the host
#                intact), and so must register-timer's (daprd names the actor it got); schedule /
#                register-reminder must come back as well-formed WIT results (see below why not more).
#
# Needs: Docker (Redis on localhost:26480), the dapr CLI + daprd
# (`dapr init --slim`), wasmtime, wasm-tools, wkg, wac, wit-bindgen-scala, sbt.
# SKIP_BUILD=1 reuses wasm/target/components/*.wasm; E2E_ONLY="counter ..." runs only those components. SBT_ARGS is passed to sbt, e.g.
# SBT_ARGS=-Ddapr4s.wasm.core=source links against dapr4s's own src/shared (see wasm/build.sbt).
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
  (cd "$repo/wasm" && sbt -batch ${SBT_ARGS:-} counterRaw/dapr4sComponent counter/dapr4sComponent importsProbe/dapr4sComponent)
fi

expected_imports=$'configuration-read: \ninvocation: \nstate: save\nstate-read: get\ntypes: '

invoke() { dapr invoke --app-id "$APP_ID" --method "$1" --verb POST 2>&1 | head -1; }

for v in ${E2E_ONLY:-counterRaw counter importsProbe}; do
  [[ "$v" == importsProbe ]] && continue
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
# ── importsProbe: WIT imports whose params go through linear memory (> 16 flat params) ─────────────
if [[ " ${E2E_ONLY:-importsProbe} " != *" importsProbe "* ]]; then
  echo "e2e: OK"
  exit 0
fi
v=importsProbe
echo "== $v"
got_imports="$("$here/imported-functions.py" "$components/$v.wasm")"
for want in "actors: register-reminder,register-timer" "conversation: converse" "jobs: schedule"; do
  grep -qxF "$want" <<<"$got_imports" || fail "$v: expected the import line '$want' in:"$'\n'"$got_imports"
done
echo "$v: imports jobs.schedule, conversation.converse, actors.register-reminder/-timer (all > 16 flat params)"
"$here/compose.sh" "$components/$v.wasm" "$components/composed-$v.wasm" >"$logs/compose-$v.log" 2>&1 ||
  fail "$v: compose"
"$here/run.sh" "$components/composed-$v.wasm" >"$logs/run-$v.log" 2>&1 &
up=0
for _ in $(seq 1 90); do
  grep -q "up and running" "$logs/run-$v.log" && { up=1; break; }
  sleep 1
done
[[ "$up" == 1 ]] || fail "$v: sidecar + component did not come up"
sleep 2
conv="$(invoke converse)"
echo "$v: converse -> $conv"
[[ "$conv" == error:*"conversation component no-such-llm"* ]] || fail "$v: converse returned '$conv'"
# schedule / register-reminder: the call must come back as a well-formed WIT result (a params tuple the
# host cannot lift traps the instance instead). Their happy paths need more than this harness has: actor
# hosting with placement, and the 0.7.1 wasi-http provider calls daprd 1.17 on `/v1.0/jobs/...`, which
# daprd does not route ("failed getting app id").
for m in schedule register-reminder; do
  r="$(invoke "$m")"
  echo "$v: $m -> $r"
  [[ "$r" == ok:* || "$r" == error:* ]] || fail "$v: $m returned '$r'"
done
# register-timer: daprd answers HTTP 500 ERR_ACTOR_TIMER_CREATE naming the actor it got, which the 0.7.1
# provider does not map to a WIT error but traps on (logging daprd's message). The actor in that message
# must be exactly the one the probe sent.
r="$(invoke register-timer || true)"
sleep 1
if [[ "$r" == ok:* || "$r" == error:* ]]; then
  echo "$v: register-timer -> $r"
else
  grep -q "can't create timer for actor ProbeActor||probe-1" "$logs/run-$v.log" ||
    fail "$v: register-timer returned '$r' and daprd did not name ProbeActor||probe-1"
  echo "$v: register-timer -> daprd: \"can't create timer for actor ProbeActor||probe-1: actor type not registered\" (the provider traps on it)"
fi
stop_app
echo "e2e: OK"
