# JVM ↔ Scala.js test & config parity

Status: done (PR #38). Goal: the JVM and Scala.js variants should share as much
production code, test logic, and Dapr configuration as is *reasonable*, and have equal
integration-test coverage for every cross-platform capability.

Outcome: one shared component set (`scripts/it/components`, redis everywhere), shared
scenario traits in `test/shared/scenarios` driving both platforms, and thin per-platform
shells. Direct-call capabilities (state, secrets, lock, crypto, configuration, invoke)
share the full call+assertion logic; server-delivery suites (actor, workflow, pub/sub
delivery) keep platform-specific bring-up but run on the same shared redis components.
Verified locally: all JVM integration suites + the full JS Wasm+JSPI leg green.

## What is already shared (baseline)

- All platform-agnostic production code lives in `src/shared` (models, opaque types, the
  capability traits, the whole `derivation` package). Platform code is the thin
  `*Impl`/`*Platform` layer in `src/jvm` and `src/js`.
- The capture-checked **direct style** means call sites are *identical* across platforms:
  `StateCapability.get(key)` returns `Option[...]` directly on both (JSPI hides the JS async).
- Test apps/fixtures (`EchoService`, `IncrRequest`, `CounterState`, the workflow/actor apps)
  already live in `test/shared/apps`.
- `Jobs` and `Conversation` are legitimately JVM-only (absent from the Dapr JS SDK); the
  platform trait makes them compile-time absent on JS, so they correctly have no JS tests.

## Gaps this work closes

### 1. Dapr component definitions (the `configstore.yaml`-without-a-JVM-twin smell)

Previously the two platforms described the *same* components two different ways **and with
different backends**:

| component | JVM (before) | JS (before) | unified |
| --- | --- | --- | --- |
| state | `state.in-memory` | `state.redis` | **`state.redis`** (actorStateStore) |
| pubsub | `pubsub.in-memory` | `pubsub.redis` | **`pubsub.redis`** |
| secrets | `secretstores.local.env` | `secretstores.local.file` | **`secretstores.local.file`** |
| configuration | `configuration.redis` | `configuration.redis` | `configuration.redis` |
| lock | `lock.redis` | `lock.redis` | `lock.redis` |
| crypto | `crypto.dapr.localstorage` | `crypto.dapr.localstorage` | `crypto.dapr.localstorage` |

**Unification:** one canonical set under `scripts/it/components/*.yaml` is the single source
of truth. The only environment-specific value is `redisHost`, kept as a `${DAPR4S_IT_REDIS_HOST}`
placeholder and rendered per topology:

- **JS** topology: `--network host`, redis on host port 6391 → `localhost:6391`, daprd reads the
  rendered files via `--resources-path` (as today).
- **JVM** topology: testcontainers shared `Network`, redis alias `redis` → `redis:6379`, fed to
  `io.dapr.testcontainers.DaprContainer.withComponent(java.nio.file.Path)` (the file-ingesting
  overload — verified present in testcontainers-dapr 1.17.2).

In-container paths are standardized to `/dapr4s-it` so crypto (`/dapr4s-it/keys`) and secrets
(`/dapr4s-it/secrets.json`) need no templating — only `redisHost` does.

### 2. Integration-test coverage / naming

- Every cross-platform capability has an integration suite on **both** platforms.
- Close the remaining gaps (e.g. JS had no invoke/secrets *error-path* twin of the JVM
  `*IntegrationTest`).
- One naming scheme across platforms.

### 3. Shared scenario logic

The *bring-up* (per-suite testcontainers vs one external Docker+Node sidecar) and the *munit
boundary* (synchronous on JVM vs `Future` via `js.async{}.toFuture` on JS) genuinely cannot be
shared. The **scenario** (the capability calls + assertions) can.

**Design:** each capability's direct-call scenarios become methods on a shared trait in
`test/shared` with `self: munit.Assertions =>`, bodies using only the shared API and a
`given DaprCapability`. Both platforms mix the trait in; each suite owns only bring-up and the
async wrapper, then calls the shared scenario.

```scala
// test/shared — shared, compiles on both platforms
trait StateScenarios { self: munit.Assertions =>
  def saveThenGet(using DaprCapability): Unit =
    val k = StateStoreKey("k"); StateCapability.save(k, "v")
    assertEquals(StateCapability.get[String](k), Some("v"))
}
// JVM shell: test("..."){ withDapr { saveThenGet } }            (synchronous)
// JS shell:  test("..."){ js.async { Dapr(cfg).run { saveThenGet } }.toFuture }
```

**Boundary discovered:** the JVM `*CapabilityServerTest` suites call the capability *from inside
a `DaprAppServer` route handler over HTTP* — they additionally exercise server route dispatch.
The shared scenario backbone targets the **direct-call** form (what the JVM `*IntegrationTest`
suites and all JS suites already do). Server-dispatch coverage stays a per-platform layer
(JVM `DaprAppServer` routes; JS `JsTestServer`), since the server runtimes differ.

## Execution order (incremental, format → compile → test after each) — all done

1. ✅ Canonical `scripts/it/components/*.yaml` + `render-components.sh`.
2. ✅ JS harness assembles the rendered dir (components + secrets.json + RSA key) at `/dapr4s-it`.
3. ✅ JVM suites on the shared set: `SharedDaprItSuite` (direct-call) + `RedisFixture` (server-delivery);
   state in-memory→redis, secrets local.env→local.file, actor/workflow/pubsub/app-level in-memory→redis.
4. ✅ Shared scenario traits + thin shells for the direct-call capabilities + invoke; JS invoke error-path gap closed.
5. ✅ Verified locally — every JVM integration suite + the full JS Wasm+JSPI leg pass on the shared redis components.
