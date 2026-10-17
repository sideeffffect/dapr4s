#!/usr/bin/env bash
# End-to-end test of the `wasm` platform. Builds the test components, then for each:
#   1. checks the WIT functions it imports (W1 authority: an app imports only what its code reaches);
#   2. composes it with the dapr-wasm-components 0.7.5 wasi-http providers (compose.sh);
#   3. runs it in `wasmtime serve` next to daprd (`dapr run`, run.sh) and calls it through the sidecar.
# Components:
#   counterRaw   (test/wasm/counter-raw: bindings + glue, no wall)   counter -> 1, then 2
#   counter      (test/wasm/counter: dapr4s's API)                    counter -> 1, then 2; error mapping:
#                missing-store -> ComponentNotFoundException(State, "no-such-store"); stale-etag ->
#                ETagMismatchException and the stale write does not land (counter -> 3); java-time -> java.time
#                parsing/formatting/localized text/default zone (UTC) without a JS host
#   importsProbe (test/wasm/imports-probe): the four imports with > 16 flat params, which only the
#                patched linker (dapr4s-wasm-linker) lowers: it must import exactly them; `converse` to daprd's
#                echo component must answer the very text the probe sent (the params reached the host intact);
#                `schedule` must succeed (a scheduler runs for it); register-timer / register-reminder on an
#                actor type this app does not host must be daprd's typed refusal (PermissionDenied), the timer's
#                naming the very actor the probe sent.
#
# Needs: Docker (Redis on localhost:26480; for importsProbe also the daprio/dapr scheduler of the installed
# daprd's version on localhost:50006), the dapr CLI + daprd (`dapr init --slim`, which has no placement or
# scheduler), wasmtime, wasm-tools, wkg, wac, wit-bindgen-scala, sbt.
# SKIP_BUILD=1 reuses wasm/target/components/*.wasm; E2E_ONLY="counter ..." runs only those components. SBT_ARGS is passed to sbt, e.g.
# SBT_ARGS=-Ddapr4s.wasm.core=source links against dapr4s's own src/shared (see wasm/build.sbt).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
components="$repo/wasm/target/components"
logs="$repo/wasm/target/e2e"
redis=dapr4s-wasm-redis
scheduler=dapr4s-wasm-scheduler
export APP_ID=dapr4s-wasm APP_PORT=18087 DAPR_HTTP_PORT=3587 DAPR_GRPC_PORT=50087
mkdir -p "$logs"

started_redis=0
started_scheduler=0
stop_app() {
  dapr stop --app-id "$APP_ID" >/dev/null 2>&1 || true
  pkill -f "^wasmtime serve -S cli --addr 127.0.0.1:$APP_PORT" 2>/dev/null || true
  sleep 2
}
cleanup() {
  stop_app
  [[ "$started_redis" == 1 ]] && docker rm -f "$redis" >/dev/null 2>&1 || true
  [[ "$started_scheduler" == 1 ]] && docker rm -f "$scheduler" >/dev/null 2>&1 || true
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

# The jobs API needs daprd's scheduler, which `dapr init --slim` does not install: run the one of the
# installed daprd's version on the port daprd dials by default (localhost:50006), only for importsProbe.
if [[ " ${E2E_ONLY:-importsProbe} " == *" importsProbe "* ]] &&
  ! docker ps --format '{{.Names}}' | grep -qx "$scheduler"; then
  docker run -d --rm --name "$scheduler" -p 50006:50006 "daprio/dapr:$(daprd --version)" \
    ./scheduler --port 50006 --etcd-data-dir /tmp/etcd >/dev/null
  started_scheduler=1
  ready=0
  for _ in $(seq 1 60); do
    docker logs "$scheduler" 2>&1 | grep -q "api is ready" && { ready=1; break; }
    sleep 1
  done
  [[ "$ready" == 1 ]] || fail "the scheduler did not come up"
fi

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  (cd "$repo/wasm" && sbt -batch ${SBT_ARGS:-} counterRaw/dapr4sComponent counter/dapr4sComponent importsProbe/dapr4sComponent)
fi

# counter's java-time route: java.time parsing, ISO/RFC formatting, localized text, the default zone, fixed zones.
expected_java_time='"instant=2026-10-17T10:15:30Z;date=2026-10-18;zoned=2026-10-17T12:15:30+02:00;same=true;'\
'iso=2026-10-17T05:15:30-05:00;rfc=Sat, 17 Oct 2026 12:15:30 +0200;day=Saturday;default=UTC;'\
'utc=2026-10-17T10:15:30Z[UTC];duration=PT1H45M"'

expected_imports=$'configuration-read: \ninvocation: \nstate: save\nstate-read: get\ntypes: '

# First line of the reply. Not `| head -1`: once head exits, a dapr that still writes gets SIGPIPE, and
# under pipefail + set -e the whole script dies with exit 141 (seen in CI). Returns dapr's status, as before.
invoke() {
  local out rc=0
  out="$(dapr invoke --app-id "$APP_ID" --method "$1" --verb POST 2>&1)" || rc=$?
  printf '%s\n' "${out%%$'\n'*}"
  return "$rc"
}

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
    jt="$(invoke java-time)"
    echo "$v: java-time -> $jt"
    [[ "$jt" == "$expected_java_time" ]] || fail "$v: java-time returned '$jt', expected '$expected_java_time'"
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
[[ "$conv" == "ok: hello from scala-wasm" ]] || fail "$v: converse returned '$conv'"
sched="$(invoke schedule)"
echo "$v: schedule -> $sched"
[[ "$sched" == "ok: scheduled probe-job" ]] || fail "$v: schedule returned '$sched'"
# The app hosts no actor type, so daprd refuses both (a reminder with 403, a timer with a generic 500 that
# the 0.7.5 providers map to the same typed error). That is the deterministic answer without placement.
rem="$(invoke register-reminder)"
echo "$v: register-reminder -> $rem"
[[ "$rem" == "error: PermissionDenied("*"hosted actor types"*")" ]] || fail "$v: register-reminder returned '$rem'"
timer="$(invoke register-timer)"
echo "$v: register-timer -> $timer"
[[ "$timer" == "error: PermissionDenied("*"can't create timer for actor ProbeActor||probe-1"*")" ]] ||
  fail "$v: register-timer returned '$timer'"
stop_app
echo "e2e: OK"
