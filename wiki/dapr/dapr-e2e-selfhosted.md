# Dapr E2E Testing — Self-Hosted (dapr run) Pattern

> Sources: dapr4s-examples project (this repo), 2026-05-27; practical experience building 7-module E2E suite
> Updated: 2026-05-27

## Problem Statement

When the application under test is a **JVM process on the host** (not a Docker image), `DaprContainer` from `io.diagrid.dapr:testcontainers-dapr` is unnecessarily complex: it spins up `daprd` inside Docker and requires `Testcontainers.exposeHostPorts(port)` plus `withAppChannelAddress("host.testcontainers.internal")` to route traffic back to the host JVM. For host-resident JVM apps, the `dapr run -- java ...` CLI pattern is simpler and more transparent.

See [dapr-testcontainers.md](dapr-testcontainers.md) for the `DaprContainer` approach (appropriate when the app itself is containerized).

## The `dapr run -- java -cp <jar>` Pattern

### Core Idea

```
dapr run \
  --app-id    <id> \
  --app-port  <port> \          # omit for one-shot (no server)
  --dapr-http-port <port> \
  --components-path <dir> \
  --log-level error \
  -- java -cp <assembly-jar> <mainClass>
```

Dapr CLI spawns `daprd` (the sidecar) and then runs the JVM. Both are child processes of `dapr`. When `dapr stop --app-id <id>` is called, both are terminated cleanly.

### Two Modes: Server vs. One-Shot

**Server mode** — long-lived process that handles Dapr callbacks (pubsub, actors, workflows):
- Pass `--app-port`; omit from one-shot commands.
- Keep `os.SubProcess` reference; destroy it in `afterAll`.
- The app's HTTP server must be up before sending requests — poll with `waitForPort`.

**One-shot mode** — runs to completion, returns stdout:
- No `--app-port` needed.
- Use `os.proc(...).call(timeout = ...)` — waits for process exit, returns `os.CommandResult`.
- Assert on `result.out.text()` content.

## Infrastructure: Redis via Testcontainers

External state backends (Redis here) are managed by Testcontainers in the test JVM. The key issue in **Scala 3** is the self-referential Java generic on `GenericContainer`:

```java
// Java: class GenericContainer<SELF extends GenericContainer<SELF>>
```

In Scala 3, write a concrete subclass to satisfy the bound:

```scala
import org.testcontainers.containers.GenericContainer as TC
import org.testcontainers.utility.DockerImageName

private class RedisContainer extends TC[RedisContainer](DockerImageName.parse("redis:7"))
```

This avoids raw-type warnings and compiles cleanly. Calling `new TC[TC[?]](...)` directly doesn't satisfy the self bound.

### Dynamic Component YAMLs

Testcontainers assigns random host ports; Dapr components must be configured with the actual port. Solution: write YAML files to `os.temp.dir()` at startup, pass `--components-path` pointing there.

```scala
private def writeComponents(redisPort: Int): os.Path =
  val dir = os.temp.dir(prefix = "dapr-e2e-")
  os.write(dir / "statestore.yaml", s"""
    |apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: statestore
    |spec:
    |  type: state.redis
    |  version: v1
    |  metadata:
    |    - name: redisHost
    |      value: localhost:$redisPort
    |    - name: redisPassword
    |      value: ""
    |    - name: actorStateStore
    |      value: "true"
    """.stripMargin.trim)
  // ... write pubsub.yaml, lockstore.yaml, etc.
  dir
```

## Mill Integration: `forkArgs` + `assembly()`

### The Deadlock Problem

Running `mill <module>.runMain` from within `mill e2e.test` **deadlocks**: the outer Mill process holds a build lock that the inner Mill process tries to acquire.

### The Fix: Pre-built Jars via `forkArgs`

Declare the assembly JARs as dependencies of the test task. Mill builds them before running tests, then passes the paths as system properties:

```scala
// in build.mill
object e2e extends BaseModule with TestModule.Munit:
  override def forkArgs: T[Seq[String]] = Task {
    Seq(
      s"-De2e.projectRoot=${os.pwd}",
      s"-De2e.jar.hello-state=${`01-hello-state-shell`.assembly().path}",
      s"-De2e.jar.hello-pubsub=${`03-hello-pubsub-shell`.assembly().path}",
      // ... one entry per module
    )
  }
```

Test code reads the paths:
```scala
object Harness:
  def jarFor(module: String): os.Path =
    os.Path(System.getProperty(s"e2e.jar.$module"))
```

Now tests use `java -cp <jar>` directly — no Mill subprocess needed at test time.

## E2E Infrastructure Singleton

Start Redis once per JVM, share across all test suites, flush between each test:

```scala
object E2EInfra:
  // ... (see E2EInfra.scala)
  def ensureStarted(): Unit = lock.synchronized:
    if !running then
      _redis = RedisContainer()
      _redis.addExposedPort(6379)
      _redis.start()
      _componentsDir = writeComponents(_redis.getMappedPort(6379))
      sys.addShutdownHook { _redis.stop() }
      running = true
```

MUnit suite base:
```scala
abstract class E2ESuite extends munit.FunSuite:
  override def beforeAll(): Unit =
    E2EInfra.ensureStarted()
    super.beforeAll()
  override def beforeEach(context: munit.BeforeEach): Unit =
    E2EInfra.flushRedis()
    super.beforeEach(context)
```

## Harness API

```scala
object Harness:
  // One-shot: runs app to completion, returns stdout
  def runOneShot(appId: String, jarModule: String, mainClass: String,
    daprPort: Int, envVars: Map[String, String] = Map.empty,
    timeoutMs: Long = 120_000): String

  // Server: spawns long-lived process, returns handle
  def spawnServer(appId: String, jarModule: String, mainClass: String,
    appPort: Int, daprPort: Int,
    envVars: Map[String, String] = Map.empty): os.SubProcess

  // Teardown: stops sidecar + process
  def stopApp(appId: String, proc: os.SubProcess): Unit

  // Poll until port accepts connections
  def waitForPort(port: Int, timeoutMs: Long = 60_000): Unit
```

## Port Conventions (dapr4s-examples)

| Module            | Dapr HTTP port | App port |
|-------------------|---------------|----------|
| hello-state       | 3501          | (n/a)    |
| secrets-config    | 3502          | (n/a)    |
| hello-pubsub      | 3503          | 8083     |
| service-invocation| 3504          | 8084     |
| distributed-lock  | 3505          | (n/a)    |
| actors            | 3506          | 8086     |
| workflows         | 3507          | 8087     |

## Module-Specific Notes

### Pub/Sub subscriber

After `waitForPort`, sleep 2 seconds: Dapr must make a callback to `/dapr/subscribe` before it registers subscriptions. Without the sleep, publish requests return 204 but the subscriber sees nothing.

### Actors

After `waitForPort`, sleep 3 seconds: the placement service needs time to receive the actor type registration from `daprd`. Requests to invoke actor methods before that will fail with placement errors.

### Workflows

Use the beta HTTP API:
- Start: `POST /v1.0-beta1/workflows/dapr/{workflowName}/start?instanceID={id}`
- Query: `GET /v1.0-beta1/workflows/dapr/{instanceId}`
- Poll `runtimeStatus` for `"COMPLETED"`, `"FAILED"`, or `"TERMINATED"`.

### Secrets (local file provider)

`secretfile` secret store requires no Redis. Write the secrets JSON file to a temp dir, pass path via env var (`SECRET_DIR`). No component YAML needed for the file provider — configure via standard Dapr component definition.

### Calling service invocation callees directly

Tests that verify a callee's handler logic can call the **app HTTP port directly** (bypassing the Dapr sidecar). The app HTTP server exposes the same routes (`/{methodName}`) that `daprd` calls internally. This avoids needing a second sidecar for the caller.

## Why Not `DaprContainer` / `testcontainers-dapr`

`io.diagrid.dapr:testcontainers-dapr` (`DaprContainer`) is the right tool when:
- Your app runs **inside Docker** (it's a container image).
- You want `daprd` lifecycle managed by Testcontainers annotations (`@Container`).
- You use the Dapr Java SDK client (not raw HTTP).

It's awkward for host-JVM apps because:
1. `daprd` (in Docker) must reach the app (on host) via `host.testcontainers.internal`.
2. `Testcontainers.exposeHostPorts(port)` must be called before container start.
3. The DaprClient uses gRPC to talk to the containerized `daprd`.
4. No easy way to get `dapr run`-style stdout from the app process.

The `dapr run -- java -cp <jar>` approach keeps both the app and `daprd` on the host, matching the local development experience exactly.

## Running

```bash
mill e2e.test
```

Mill builds all assembly JARs first (via `forkArgs` dependency), then runs the MUnit suite. Requires:
- `dapr` CLI on `$PATH` (initialized with `dapr init`)
- Docker daemon running (for Redis container)
- Redis image pulled (`docker pull redis:7`)

## See Also

- [dapr-testcontainers.md](dapr-testcontainers.md) — `DaprContainer` for containerized apps
- [testcontainers-overview.md](../testing/testcontainers-overview.md) — Testcontainers fundamentals
- [dapr-overview.md](dapr-overview.md) — Dapr building blocks overview
