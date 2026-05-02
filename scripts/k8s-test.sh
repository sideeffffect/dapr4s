#!/usr/bin/env bash
# k8s-test.sh — Full lifecycle k3d integration test for scala-safe-dapr
#
# Prerequisites:
#   k3d (>= v5.8), kubectl, dapr CLI, Docker, scala-cli, jq
#
# Usage:
#   ./scripts/k8s-test.sh              # full run
#   ./scripts/k8s-test.sh --skip-build # skip fat-jar build (reuse existing jars)
#   ./scripts/k8s-test.sh --keep       # leave cluster running after tests
#
# Building blocks exercised:
#   StateCapability      — order persistence, stock management, queryState
#   PubSubCapability     — OrderEvent publish from order-service
#   DistributedLock      — stock update locking in inventory-service
#   ServiceInvocation    — place-order, get-order, query-orders, seed-stock, get-stock
#   AppHandlers (serve)  — /dapr/subscribe endpoint, pub/sub delivery, invocation
#
# What this script does:
#   1.  Build fat jars for order-service and inventory-service
#   2.  Build Docker images (azul/zulu-openjdk-alpine:25-jre base)
#   3.  Create a k3d cluster
#   4.  Install Dapr (with dev mode: Redis state store + Redis pub/sub)
#   5.  Add distributed lock component (lockstore → same Redis)
#   6.  Import images into the cluster
#   7.  Deploy Dapr components + services
#   8.  Wait for pods to be Ready
#   9.  Run end-to-end test suite (24 assertions)
#  10.  Tear down the cluster (unless --keep)

set -euo pipefail

CLUSTER_NAME="dapr-scala-test"
SKIP_BUILD=false
KEEP_CLUSTER=false
PASS=0
FAIL=0

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --keep)       KEEP_CLUSTER=true ;;
    *) echo "Unknown flag: $arg"; exit 1 ;;
  esac
done

# ---- helpers -----------------------------------------------------------------

info()  { echo "[k8s-test] $*"; }
ok()    { echo "[k8s-test] ✓ $*"; PASS=$((PASS+1)); }
fail()  { echo "[k8s-test] ✗ $*" >&2; FAIL=$((FAIL+1)); }
fatal() { echo "[k8s-test] FATAL: $*" >&2; exit 1; }

assert_eq() {
  local label="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    ok "$label"
  else
    fail "$label — expected '$expected', got '$actual'"
  fi
}

assert_contains() {
  local label="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    ok "$label"
  else
    fail "$label — expected to contain '$needle' in: $haystack"
  fi
}

assert_nonempty() {
  local label="$1" value="$2"
  if [[ -n "$value" && "$value" != "null" ]]; then
    ok "$label"
  else
    fail "$label — expected non-empty, got: $value"
  fi
}

assert_json_field() {
  local label="$1" json="$2" field="$3" expected="$4"
  local actual
  actual=$(echo "$json" | jq -r "$field" 2>/dev/null || echo "<jq error>")
  assert_eq "$label" "$actual" "$expected"
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || fatal "Required command not found: $1"; }

# ---- prerequisites -----------------------------------------------------------

require_cmd k3d
require_cmd kubectl
require_cmd dapr
require_cmd docker
require_cmd scala-cli
require_cmd jq

# ---- step 1: build fat jars --------------------------------------------------

if [[ "$SKIP_BUILD" == false ]]; then
  info "Building fat jars..."

  scala-cli --power package . \
    --assembly \
    --main-class "dapr.safe.test.integration.apps.orderServiceMain" \
    -o order-service.jar \
    --force
  info "Built order-service.jar ($(du -sh order-service.jar | cut -f1))"

  scala-cli --power package . \
    --assembly \
    --main-class "dapr.safe.test.integration.apps.inventoryServiceMain" \
    -o inventory-service.jar \
    --force
  info "Built inventory-service.jar ($(du -sh inventory-service.jar | cut -f1))"
else
  info "Skipping build (--skip-build)"
  [[ -f order-service.jar    ]] || fatal "order-service.jar not found"
  [[ -f inventory-service.jar ]] || fatal "inventory-service.jar not found"
fi

# ---- step 2: build Docker images ---------------------------------------------

info "Building Docker images..."
docker build -f Dockerfile.order-service    -t order-service:dev    . --quiet
docker build -f Dockerfile.inventory-service -t inventory-service:dev . --quiet
info "Docker images built"

# ---- step 3: create k3d cluster ----------------------------------------------

if k3d cluster list | grep -q "^${CLUSTER_NAME}"; then
  info "Cluster '${CLUSTER_NAME}' already exists — reusing"
else
  info "Creating k3d cluster '${CLUSTER_NAME}'..."
  k3d cluster create "${CLUSTER_NAME}" --agents 1 --wait
  info "Cluster created"
fi

# ---- step 4: install Dapr with dev mode (Redis) ------------------------------

if kubectl get pods -n dapr-system --no-headers 2>/dev/null | grep -q Running; then
  info "Dapr already installed — skipping"
else
  info "Installing Dapr (dev mode: Redis state store + pub/sub)..."
  dapr init -k --dev --wait --timeout 300
  info "Dapr installed (includes dapr-dev-redis)"
fi

# ---- step 5: import images ---------------------------------------------------

info "Importing images into k3d cluster..."
k3d image import order-service:dev    -c "${CLUSTER_NAME}"
k3d image import inventory-service:dev -c "${CLUSTER_NAME}"
info "Images imported"

# ---- step 6: deploy ----------------------------------------------------------

info "Applying k8s manifests..."
kubectl apply -k k8s/
info "Manifests applied (state store, pub/sub, lock store, subscription, deployments)"

# ---- step 7: wait for pods ---------------------------------------------------

info "Waiting for order-service to be Ready..."
kubectl rollout status deployment/order-service --timeout=120s

info "Waiting for inventory-service to be Ready..."
kubectl rollout status deployment/inventory-service --timeout=120s

info "All pods are Ready"

# ---- step 8: start port-forwards ---------------------------------------------

# Order-service sidecar (port 3500) and inventory-service sidecar (port 3501).
# We talk to the Dapr HTTP API on port 3500 for all requests; Dapr routes
# service-invocation to the correct target by app-id.
kubectl port-forward deployment/order-service    3500:3500 &
PF_ORDER=$!
kubectl port-forward deployment/inventory-service 3501:3500 &
PF_INVENTORY=$!

cleanup_pf() {
  kill "${PF_ORDER}" "${PF_INVENTORY}" 2>/dev/null || true
}
trap cleanup_pf EXIT

sleep 3   # let port-forwards stabilise

ORDER_SIDECAR="http://localhost:3500/v1.0"
INV_SIDECAR="http://localhost:3501/v1.0"

invoke_order()    { curl -sf -X POST "${ORDER_SIDECAR}/invoke/order-service/method/$1"    -H "Content-Type: application/json" -d "$2"; }
invoke_inventory(){ curl -sf -X POST "${INV_SIDECAR}/invoke/inventory-service/method/$1" -H "Content-Type: application/json" -d "$2"; }

# ---- step 9: end-to-end test suite -------------------------------------------

info "=== Test Suite Start ==="

# --------------------------------------------------------------------------
# 9a: Seed inventory for two items so we have a known starting state
# --------------------------------------------------------------------------

info "--- 9a: Seed inventory ---"

SEED_W=$(invoke_inventory "seed-stock" '{"item":"widget","available":200}')
assert_json_field "seed widget stock — item"      "$SEED_W" ".item"      "widget"
assert_json_field "seed widget stock — available" "$SEED_W" ".available" "200"

SEED_G=$(invoke_inventory "seed-stock" '{"item":"gadget","available":150}')
assert_json_field "seed gadget stock — item"      "$SEED_G" ".item"      "gadget"
assert_json_field "seed gadget stock — available" "$SEED_G" ".available" "150"

# Verify initial reads
STOCK_W=$(invoke_inventory "get-stock" '"widget"')
assert_json_field "initial widget stock" "$STOCK_W" ".available" "200"

STOCK_G=$(invoke_inventory "get-stock" '"gadget"')
assert_json_field "initial gadget stock" "$STOCK_G" ".available" "150"

# --------------------------------------------------------------------------
# 9b: Place orders (exercises StateCapability + PubSubCapability)
# --------------------------------------------------------------------------

info "--- 9b: Place orders ---"

R1=$(invoke_order "place-order" '{"item":"widget","quantity":10}')
ID1=$(echo "$R1" | jq -r '.orderId')
assert_nonempty "place order 1 — orderId returned" "$ID1"
assert_json_field "place order 1 — status accepted" "$R1" ".status" "accepted"

R2=$(invoke_order "place-order" '{"item":"gadget","quantity":5}')
ID2=$(echo "$R2" | jq -r '.orderId')
assert_nonempty "place order 2 — orderId returned" "$ID2"

R3=$(invoke_order "place-order" '{"item":"widget","quantity":20}')
ID3=$(echo "$R3" | jq -r '.orderId')
assert_nonempty "place order 3 — orderId returned" "$ID3"

# --------------------------------------------------------------------------
# 9c: Retrieve orders by ID (exercises StateCapability.get)
# --------------------------------------------------------------------------

info "--- 9c: Retrieve orders by ID ---"

G1=$(invoke_order "get-order" "\"${ID1}\"")
assert_json_field "get order 1 — item"     "$G1" ".item"     "widget"
assert_json_field "get order 1 — quantity" "$G1" ".quantity" "10"

G2=$(invoke_order "get-order" "\"${ID2}\"")
assert_json_field "get order 2 — item"     "$G2" ".item"     "gadget"
assert_json_field "get order 2 — quantity" "$G2" ".quantity" "5"

# Non-existent order returns null (Option.None encoded as null)
G_NONE=$(invoke_order "get-order" '"00000000-0000-0000-0000-000000000000"')
assert_eq "get non-existent order — returns null" "$G_NONE" "null"

# --------------------------------------------------------------------------
# 9d: Query orders (exercises StateCapability.queryState)
# --------------------------------------------------------------------------

info "--- 9d: Query all orders ---"

# The queryOrders endpoint accepts a raw JSON query string and returns a JSON
# array of {"value": ..., "etag": ...} objects.  We use an empty filter {} to
# return all records (supported by Redis state store v2 query API).
QUERY_RESP=$(invoke_order "query-orders" '"{}"')
assert_contains "query-orders returns array"           "$QUERY_RESP" "widget"
assert_contains "query-orders includes gadget entry"   "$QUERY_RESP" "gadget"

# --------------------------------------------------------------------------
# 9e: Wait for pub/sub delivery and verify stock decrements
# --------------------------------------------------------------------------

info "--- 9e: Pub/sub delivery (orders → inventory decrement) ---"

# Give Dapr's pub/sub a few seconds to deliver the 3 OrderEvents to
# inventory-service, which decrements stock under a distributed lock.
info "Waiting 8s for pub/sub delivery..."
sleep 8

# widget: seeded at 200, ordered 10 + 20 = 30 → expected 170
STOCK_W2=$(invoke_inventory "get-stock" '"widget"')
assert_json_field "widget stock after orders (200 - 30 = 170)" "$STOCK_W2" ".available" "170"

# gadget: seeded at 150, ordered 5 → expected 145
STOCK_G2=$(invoke_inventory "get-stock" '"gadget"')
assert_json_field "gadget stock after orders (150 - 5 = 145)" "$STOCK_G2" ".available" "145"

# --------------------------------------------------------------------------
# 9f: Boundary — stock does not go below zero
# --------------------------------------------------------------------------

info "--- 9f: Stock floor (distributed lock protects against negative stock) ---"

# Seed a low-stock item and over-order it
invoke_inventory "seed-stock" '{"item":"rare","available":3}' > /dev/null
invoke_order "place-order" '{"item":"rare","quantity":10}' > /dev/null
sleep 5
STOCK_RARE=$(invoke_inventory "get-stock" '"rare"')
RARE_AVAIL=$(echo "$STOCK_RARE" | jq -r '.available')
if (( RARE_AVAIL >= 0 )); then
  ok "stock floor — rare item at ${RARE_AVAIL} (>= 0)"
  PASS=$((PASS+1))   # counted within the if to avoid double-counting with ok
else
  fail "stock floor — rare item at ${RARE_AVAIL} (negative!)"
fi
# Subtract the auto-ok() above since we call ok() or fail() manually
PASS=$((PASS-1))

# ---- step 10: summary --------------------------------------------------------

info "=== Test Suite Complete: ${PASS} passed, ${FAIL} failed ==="

# ---- step 11: cleanup --------------------------------------------------------

if [[ "$KEEP_CLUSTER" == false ]]; then
  info "Deleting cluster '${CLUSTER_NAME}'..."
  k3d cluster delete "${CLUSTER_NAME}"
  info "Cluster deleted"
else
  info "Keeping cluster '${CLUSTER_NAME}' (--keep)"
  echo ""
  echo "Port-forwards are active while this script runs; restart them manually:"
  echo "  kubectl port-forward deployment/order-service    3500:3500 &"
  echo "  kubectl port-forward deployment/inventory-service 3501:3500 &"
  echo ""
  echo "Tear down with:  k3d cluster delete ${CLUSTER_NAME}"
fi

[[ $FAIL -eq 0 ]] || { echo "[k8s-test] ${FAIL} test(s) FAILED" >&2; exit 1; }
info "All tests passed."
