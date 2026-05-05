# Testcontainers Overview

> Sources: Testcontainers official docs, 2026-05-05; Java Testcontainers docs (java.testcontainers.org), 2026-05-05; jasondl.ee inter-container comms, 2024; Deepwiki Ryuk docs, 2026-05-05
> Raw: [testcontainers-overview](../../raw/testing/2026-05-05-testcontainers-overview.md)
> Updated: 2026-05-05

## What Testcontainers Is

Testcontainers is an open-source library for spinning up throwaway Docker containers during tests. Tests define their infrastructure dependencies as code; containers start before the test runs and are destroyed after. Eliminates mocks, pre-provisioned environments, and test-data pollution across parallel CI pipelines.

Supported languages: Java, Go, .NET, Node.js, Python, Rust, Haskell, Ruby, Clojure, Elixir, PHP, C.

## Internal Architecture

1. Connect to Docker daemon (socket or TCP)
2. Pull image if not cached
3. Create container with random host-port mappings for exposed ports
4. Apply wait strategy until container is ready
5. Expose connection info (`getHost()`, `getMappedPort()`) to tests
6. After test process ends: Ryuk removes all labeled resources

The key design insight is **random port mapping** — containers never conflict on the host, enabling safe parallel test runs.

## Ryuk — Resource Reaper

Ryuk (`testcontainers/ryuk`) is a sidecar container that guarantees cleanup even on abnormal JVM termination:

- Runs privileged with access to the Docker socket
- Singleton per test process (`Reaper` class manages it)
- Testcontainers labels every created resource with a session ID
- Ryuk monitors the process connection; when it closes, Ryuk removes all matching containers/networks/volumes
- Disable: `TESTCONTAINERS_RYUK_DISABLED=true` (required for Podman environments)

## Wait Strategies

Applied with `.waitingFor(strategy)`. Determine when a container is *ready* for testing.

| Strategy | Code | Use When |
|---|---|---|
| Port (default) | `Wait.forListeningPort()` | Container exposes a port |
| HTTP | `Wait.forHttp("/health").forStatusCode(200)` | REST/HTTP endpoint |
| Log message | `Wait.forLogMessage(".*Ready.*\\n", 1)` | App logs readiness |
| Healthcheck | `Wait.forHealthcheck()` | Image has HEALTHCHECK |
| Combined | `new WaitAllStrategy().withStrategy(a).withStrategy(b)` | Multiple conditions |

Custom: extend `AbstractWaitStrategy`, implement `waitUntilReady()`.

Default timeout: 60 seconds. Override: `.withStartupTimeout(Duration.ofSeconds(120))`.

## Startup Strategies

Separate from wait strategies — governs whether the container *process* itself started:

- **`IsRunningStartupCheckStrategy`** (default) — process is alive
- **`OneShotStartupCheckStrategy(duration)`** — container exits with code 0; for batch jobs
- **`MinimumDurationRunningStartupCheckStrategy(duration)`** — must stay running for N seconds

## Port Mapping

```java
container.getMappedPort(5432)    // host-side port mapped from container port 5432
container.getFirstMappedPort()   // first exposed port's mapped host port
container.getHost()              // Docker host (may differ from localhost in CI)

// Build connection URL:
String url = "jdbc:postgresql://" + container.getHost()
           + ":" + container.getMappedPort(5432) + "/db";
```

## Networking

### Default: Host to Container
Connect using `getHost()` + `getMappedPort()`. Works for most tests.

### Container to Host
When the test JVM itself is the service under test (e.g., Dapr calling back to the app):
```java
Testcontainers.exposeHostPorts(8080);        // call before container start
// Inside container: hostname "host.testcontainers.internal", port 8080
```

### Container to Container (Shared Network)
```java
Network network = Network.newNetwork();    // or Network.SHARED for simple cases

GenericContainer<?> redis = new GenericContainer<>("redis:7")
    .withNetwork(network)
    .withNetworkAliases("redis");          // hostname visible to peers on network

GenericContainer<?> app = new GenericContainer<>("myapp:latest")
    .withNetwork(network);
// app reaches redis at "redis:6379" — internal port, zero host collision risk
```

Each container belongs to at most one custom network. Use network aliases as hostnames in config files.

### Container Dependencies
```java
appContainer.dependsOn(dbContainer);   // dbContainer starts before appContainer
```

## Container Reuse (Experimental — Dev Only)

Persist containers across test runs to speed up local development:
```java
container.withReuse(true);
container.start();           // manual start; do NOT use try-with-resources
                             // do NOT call stop()
```

Enable via `~/.testcontainers.properties`:
```properties
testcontainers.reuse.enable=true
```

**Do not use in CI** — containers persist between runs, defeating isolation. Reuse keys on container configuration hash.

## Configuration Reference

Priority: env vars > `~/.testcontainers.properties` > classpath `testcontainers.properties`

```properties
# Resource reaper
ryuk.container.image=testcontainers/ryuk:0.3.3
ryuk.container.privileged=true

# Pull timeouts
pull.timeout=120
client.ping.timeout=10

# Docker connection (colons must be escaped)
docker.host=tcp\://localhost\:2375
docker.tls.verify=1
docker.cert.path=/certs
```

```bash
# Environment variables
DOCKER_HOST=unix:///var/run/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
TESTCONTAINERS_HOST_OVERRIDE=localhost
TESTCONTAINERS_RYUK_DISABLED=true
TESTCONTAINERS_REUSE_ENABLE=true
```

## Pre-Built Modules

50+ modules with correct wait strategies pre-configured:

- **Databases**: PostgreSQL, MySQL, MariaDB, MongoDB, Cassandra, Neo4j, Redis, CockroachDB, ClickHouse
- **Messaging**: Kafka, RabbitMQ, Pulsar, ActiveMQ
- **Infra/Tools**: Elasticsearch, LocalStack, Vault, Keycloak, WireMock, MockServer, Toxiproxy
- **Observability/Runtime**: Dapr, OpenTelemetry Collector, Jaeger
- **Testing**: Selenium (browsers)

## See Also

- [Testcontainers-Scala](testcontainers-scala.md)
- [Dapr Testcontainers](../dapr/dapr-testcontainers.md)
