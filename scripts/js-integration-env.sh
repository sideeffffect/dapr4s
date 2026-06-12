#!/usr/bin/env bash
# Environment for the Scala.js (Wasm + JSPI) integration tests: a real Dapr sidecar with
# Redis-backed components, the placement + scheduler services, and the dapr4s JS test server
# (test/js/integration/JsTestServer.scala) packaged to Wasm and run under Node.
#
#   scripts/js-integration-env.sh up     # start everything (idempotent: tears down first)
#   scripts/js-integration-env.sh down   # stop and remove everything
#
# Normally invoked via scripts/test-js-integration.sh, which runs the munit suites in between.
#
# == Port map (single source of truth on the infra side) ==
# All ports are NON-default to avoid collisions with a locally `dapr init`-ed stack or other
# test harnesses. The Scala twin of this table lives in test/js/integration/JsItEnv.scala —
# keep the two in sync.
#
#   redis            6391   (host port; container port 6379)
#   placement       50091   (healthz 8691, metrics 9691)
#   scheduler       51091   (healthz 8692, metrics 9692, etcd client 2391)
#   daprd HTTP       3591
#   daprd gRPC      50191   (internal gRPC 50291, metrics 9593)
#   app server       8391   (the Node test server; daprd's --app-port)
#
# == Container topology ==
# daprd, placement and scheduler run with --network host: daprd must reach the app server on
# the host (localhost:8391), the test suites must reach daprd (localhost:3591/50191), and daprd
# must reach placement/scheduler — host networking makes all of that one address space, exactly
# like the R2 manual smoke setup. Redis uses an ordinary port mapping (6391->6379); daprd
# reaches it as localhost:6391.
#
# daprd 1.17 workflows REQUIRE the scheduler service (the workflow engine schedules its
# reminders there); actors require placement. Components come from the CANONICAL shared set
# (scripts/it/components/*.yaml — the same set the JVM testcontainers suites consume): state.redis
# (actorStateStore=true), pubsub.redis, lock.redis, configuration.redis, secretstores.local.file,
# crypto.dapr.localstorage. They are rendered for this host-network topology (redisHost
# localhost:6391) by scripts/it/render-components.sh into a per-run resource dir that also holds
# the shared secrets.json and a fresh RSA key under keys/; the whole dir is mounted into daprd at
# /dapr4s-it (see up() below). The resource dir lives under .scala-build (git-ignored).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCALA_CLI="${SCALA_CLI:-scala-cli}"

DAPR_IMAGE_DAPRD="daprio/daprd:1.17.0"
DAPR_IMAGE_TOOLS="daprio/dapr:1.17.0"   # placement + scheduler binaries
REDIS_IMAGE="redis:7-alpine"

REDIS_PORT=6391
PLACEMENT_PORT=50091
SCHEDULER_PORT=51091
DAPR_HTTP_PORT=3591
DAPR_GRPC_PORT=50191
DAPR_INTERNAL_GRPC_PORT=50291
APP_PORT=8391
APP_ID="js-it-server"

C_REDIS="dapr4s-jsit-redis"
C_PLACEMENT="dapr4s-jsit-placement"
C_SCHEDULER="dapr4s-jsit-scheduler"
C_DAPRD="dapr4s-jsit-daprd"

# Server process artifacts live under .scala-build (already git-ignored by scala-cli itself).
WORK_DIR="$ROOT/.scala-build/js-it"
DIST_DIR="$WORK_DIR/dist"
PID_FILE="$WORK_DIR/server.pid"
LOG_FILE="$WORK_DIR/server.log"
# Assembled fresh per `up`: the rendered shared components/, the shared secrets.json and a fresh
# crypto key under keys/ — mounted into daprd at /dapr4s-it. Under .scala-build (git-ignored).
DAPR_DIR="$WORK_DIR/dapr"

log() { echo "[js-integration-env] $*" >&2; }

# Dump the evidence before dying: a CI runner is discarded on failure, and the interesting bits
# (the Node server crash, a daprd component-init error) live only in $LOG_FILE / `docker logs`.
dump_diagnostics() {
  if [ -f "$LOG_FILE" ]; then
    log "---- tail of $LOG_FILE ----"
    tail -n 100 "$LOG_FILE" >&2 || true
  fi
  for c in "$C_DAPRD" "$C_SCHEDULER" "$C_PLACEMENT" "$C_REDIS"; do
    if docker inspect "$c" >/dev/null 2>&1; then
      log "---- docker logs --tail 100 $c ----"
      docker logs --tail 100 "$c" >&2 || true
    fi
  done
}

die() { log "ERROR: $*"; dump_diagnostics; exit 1; }

wait_for() { # wait_for <description> <timeout-seconds> <command...>
  local desc="$1" timeout="$2"; shift 2
  local deadline=$((SECONDS + timeout))
  until "$@" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      die "timed out after ${timeout}s waiting for $desc"
    fi
    sleep 0.5
  done
  log "$desc is up"
}

down() {
  log "tearing down"
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  # Belt and braces: also kill any orphaned server by its dist path. A server can outlive its
  # PID file (e.g. a previous harness run crashed between writing the file and tearing down);
  # a stale server keeps APP_PORT bound, the next run's server dies with EADDRINUSE, and the
  # suites then talk to the stale server — whose workflow worker is dead after its daprd went
  # away (upstream task-hub-grpc-worker isFirstAttempt bug). Symptom: workflow tests time out.
  pkill -f "$DIST_DIR/main.js" 2>/dev/null || true
  docker rm -f "$C_DAPRD" "$C_SCHEDULER" "$C_PLACEMENT" "$C_REDIS" >/dev/null 2>&1 || true
  rm -rf "$DAPR_DIR"  # per-run rendered components + secrets + crypto key (regenerated by up)
}

up() {
  down  # idempotent restart

  # -- 1. Package the test server (Wasm + ES modules; the server main lives in TEST scope,
  #       hence --test: it reuses the shared test fixtures and test-only codecs).
  #       The dist dir sits inside the repo so Node's ESM resolver finds the repo-root
  #       node_modules (@dapr/dapr, express) by walking up from the module's own path.
  #
  #       The ScalablyTyped facades are compileOnly.dep (js-deps.scala), so they are absent from
  #       the RUNTIME classpath that `package` links against — unlike `compile`/`test`, which use
  #       the compile/test classpath. Without them on the link classpath the link fails with
  #       "Referring to non-existent class dapr4styped...", so add the facade jars back via
  #       scripts/st-link-jars.sh (`--jar` flags; the linker de-duplicates, no POM impact).
  log "packaging JsTestServer (Wasm) -> $DIST_DIR"
  mkdir -p "$WORK_DIR"
  local st_link
  mapfile -t st_link < <("$ROOT/scripts/st-link-jars.sh")
  "$SCALA_CLI" --power package --test --js --js-emit-wasm --js-module-kind es "$ROOT" \
    "${st_link[@]}" \
    --main-class dapr4s.test.integration.jsTestServerMain -o "$DIST_DIR" -f

  # -- 1b. Assemble the shared Dapr resource dir mounted into daprd at /dapr4s-it:
  #        components/  — the canonical scripts/it/components set rendered for this host-network
  #                       topology (redisHost localhost:6391) via scripts/it/render-components.sh;
  #        secrets.json — the shared seed (scripts/it/secrets.json);
  #        keys/rsa-key — fresh per run for crypto.dapr.localstorage. PKCS#8 PEM ("BEGIN PRIVATE
  #                       KEY"), matching the key CryptoCapabilityServerTest generates via
  #                       java.security.KeyPairGenerator on the JVM. World-readable: daprd runs as
  #                       a non-root user in the container and otherwise fails with "permission
  #                       denied". The whole dir lives under .scala-build (git-ignored).
  log "assembling shared Dapr resource dir -> $DAPR_DIR"
  command -v openssl >/dev/null || die "openssl is required to generate the crypto test key"
  rm -rf "$DAPR_DIR"
  mkdir -p "$DAPR_DIR/keys"
  "$ROOT/scripts/it/render-components.sh" "localhost:$REDIS_PORT" "$DAPR_DIR/components"
  cp "$ROOT/scripts/it/secrets.json" "$DAPR_DIR/secrets.json"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DAPR_DIR/keys/rsa-key" 2>/dev/null \
    || die "openssl failed to generate the RSA key"
  chmod -R a+rX "$DAPR_DIR"

  # -- 2. Infrastructure containers.
  log "starting redis ($C_REDIS, host port $REDIS_PORT)"
  docker run -d --name "$C_REDIS" -p "$REDIS_PORT:6379" "$REDIS_IMAGE" >/dev/null
  wait_for "redis" 60 docker exec "$C_REDIS" redis-cli ping

  log "starting placement ($C_PLACEMENT, port $PLACEMENT_PORT)"
  docker run -d --name "$C_PLACEMENT" --network host --entrypoint ./placement "$DAPR_IMAGE_TOOLS" \
    --port "$PLACEMENT_PORT" --healthz-port 8691 --metrics-port 9691 >/dev/null

  log "starting scheduler ($C_SCHEDULER, port $SCHEDULER_PORT)"
  # The scheduler embeds etcd; give it a writable tmpfs and a non-default client port.
  docker run -d --name "$C_SCHEDULER" --network host --entrypoint ./scheduler \
    --tmpfs /scheduler-data:rw,size=128m "$DAPR_IMAGE_TOOLS" \
    --port "$SCHEDULER_PORT" --healthz-port 8692 --metrics-port 9692 \
    --etcd-client-port 2391 --etcd-data-dir /scheduler-data >/dev/null

  log "starting daprd ($C_DAPRD, http $DAPR_HTTP_PORT / grpc $DAPR_GRPC_PORT)"
  docker run -d --name "$C_DAPRD" --network host \
    -v "$DAPR_DIR:/dapr4s-it:ro" \
    "$DAPR_IMAGE_DAPRD" \
    ./daprd \
    --app-id "$APP_ID" \
    --app-port "$APP_PORT" \
    --app-protocol http \
    --dapr-http-port "$DAPR_HTTP_PORT" \
    --dapr-grpc-port "$DAPR_GRPC_PORT" \
    --dapr-internal-grpc-port "$DAPR_INTERNAL_GRPC_PORT" \
    --metrics-port 9593 \
    --placement-host-address "localhost:$PLACEMENT_PORT" \
    --scheduler-host-address "localhost:$SCHEDULER_PORT" \
    --resources-path /dapr4s-it/components \
    --log-level info >/dev/null

  # -- 3. The Node test server. Started after daprd so its WorkflowRuntime connects to a gRPC
  #       endpoint that is already listening; daprd in turn polls the app port, so the order is
  #       safe in both directions.
  log "starting JsTestServer on port $APP_PORT (log: $LOG_FILE)"
  (cd "$ROOT" && nohup node "$DIST_DIR/main.js" > "$LOG_FILE" 2>&1 & echo $! > "$PID_FILE")
  wait_for "app server port $APP_PORT" 60 curl -fsS -o /dev/null "http://localhost:$APP_PORT/dapr/config"

  # daprd reports healthy only after the app channel is up and components are loaded.
  wait_for "daprd healthz" 120 curl -fsS -o /dev/null "http://localhost:$DAPR_HTTP_PORT/v1.0/healthz"

  # -- 4. Seed configuration items for ConfigurationJsIntegrationTest (Dapr's redis
  #       configuration store reads plain keys; "value||version" splits into value + version).
  log "seeding configuration keys"
  docker exec "$C_REDIS" redis-cli MSET \
    dapr4s-it-cfg-a "alpha||v1" \
    dapr4s-it-cfg-b "beta||v2" >/dev/null

  log "environment is up"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  *) die "usage: $0 up|down" ;;
esac
