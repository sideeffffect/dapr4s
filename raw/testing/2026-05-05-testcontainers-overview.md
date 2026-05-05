# Testcontainers — Core Architecture and Concepts

> Source: https://testcontainers.com/ ; https://testcontainers.com/guides/introducing-testcontainers/ ; https://java.testcontainers.org/features/startup_and_waits/ ; https://java.testcontainers.org/features/networking/ ; https://java.testcontainers.org/features/reuse/ ; https://java.testcontainers.org/features/configuration/ ; https://jasondl.ee/2024/inter-container-communications-with-testcontainers/ ; https://deepwiki.com/testcontainers/testcontainers-python/3.8-ryuk-and-resource-cleanup
> Collected: 2026-05-05
> Published: Unknown

## What Is Testcontainers

Testcontainers is an open-source library providing throwaway, lightweight Docker-based instances of databases, message brokers, browsers, or any containerized service for use in integration tests. Instead of mocks or pre-provisioned shared environments, each test run gets real, isolated services.

Used by Spotify, Netflix, Uber, JetBrains and many others. Available in 11+ languages.

## Core Architecture

Testcontainers wraps the Docker API:

1. Connects to the Docker daemon (via socket or TCP)
2. Pulls images if not cached locally
3. Creates containers with random host-port mappings for exposed ports
4. Applies wait strategies to detect readiness
5. Provides connection info to tests
6. Cleans up via Ryuk after test completion

Tests define dependencies as code — no external environment setup required, no test-data pollution across parallel pipelines.

## Ryuk — Resource Reaper

Ryuk is a sidecar container (`testcontainers/ryuk`) that automatically removes Docker resources (containers, networks, volumes) after the test process terminates, even on abnormal exit (kill, OOM, etc.).

How it works:
- Runs as a privileged container with Docker socket access
- Managed as a singleton per process via the `Reaper` class
- Testcontainers labels all created resources with a unique session ID
- Ryuk monitors the test process connection; when it closes, Ryuk removes all matching resources
- Can be disabled: `TESTCONTAINERS_RYUK_DISABLED=true` (needed for Podman, some CI)

Configure Ryuk image: `ryuk.container.image=testcontainers/ryuk:0.3.3` in `testcontainers.properties`.

## Wait Strategies

Wait strategies determine when a container is "ready" for testing. Applied with `.waitingFor(strategy)` on the container.

### Port Wait (Default)
Waits up to 60 seconds for the first exposed port to accept TCP connections. Extend with `.withStartupTimeout(Duration)`.

### HTTP Wait
```java
Wait.forHttp("/health")
    .forStatusCode(200)
    .forStatusCodeMatching(code -> code >= 200 && code < 300)
    .usingTls()                  // for HTTPS
```

### Log Message Wait
```java
Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)  // 1 occurrence
```

Useful when containers signal readiness via log output.

### Healthcheck Wait
```java
Wait.forHealthcheck()   // uses Docker HEALTHCHECK instruction in image
```

### Combined (WaitAllStrategy)
```java
new WaitAllStrategy()
    .withStrategy(Wait.forLogMessage(".*started.*\\n", 2))
    .withStrategy(Wait.forListeningPort())
```

`WaitAllStrategy` has two timeout modes:
- Default: applies outer timeout to each inner strategy
- `WITH_MAXIMUM_OUTER_TIMEOUT`: inner strategies have their own timeouts; outer throws if limit exceeded

### Custom
Extend `AbstractWaitStrategy` and implement `waitUntilReady()`.

## Startup Strategies

Separate from wait strategies — determines whether the container *process* itself started:

- `IsRunningStartupCheckStrategy` (default) — process is running
- `OneShotStartupCheckStrategy(duration)` — container exits with code 0 (batch jobs)
- `IndefiniteWaitOneShotStartupCheckStrategy` — no timeout, waits for exit
- `MinimumDurationRunningStartupCheckStrategy(duration)` — running for at least N seconds

## Port Mapping

Containers expose ports via `withExposedPorts(port, ...)`. Testcontainers maps these to random host ports:

```java
container.getMappedPort(8080)        // host port for container port 8080
container.getFirstMappedPort()       // first exposed port's mapped host port
container.getHost()                  // Docker host address (may not be localhost in CI)
```

Construct service URLs: `"jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/db"`

## Networking

### Host-to-Container (Default)
Access containers via `getHost()` and `getMappedPort()`. Default for most test scenarios.

### Container-to-Host
Expose host-side services to containers (e.g., when the test JVM IS the app):
```java
Testcontainers.exposeHostPorts(8080);   // call before container start
// Inside container: hostname "host.testcontainers.internal", port 8080
```

### Container-to-Container (Shared Network)
Create an isolated network for inter-container communication:
```java
Network network = Network.newNetwork();
// OR use predefined shared network: Network.SHARED

GenericContainer<?> redis = new GenericContainer<>("redis:7")
    .withNetwork(network)
    .withNetworkAliases("redis");       // hostname inside network

GenericContainer<?> app = new GenericContainer<>("myapp:latest")
    .withNetwork(network);
// app can reach redis at "redis:6379" (internal port, no host collision)
```

Key rule: each container can belong to only ONE custom network. `Network.SHARED` is convenient for simple cases; `Network.newNetwork()` for isolated test suites.

## Container Dependencies

```java
containerB.dependsOn(containerA);   // containerA starts before containerB
```

## Container Reuse (Experimental)

Persist containers across test runs for faster developer iteration:

```java
container.withReuse(true);
container.start();   // call manually; do NOT use try-with-resources
// do NOT call stop()
```

Enable globally: `TESTCONTAINERS_REUSE_ENABLE=true` or in `~/.testcontainers.properties`:
```properties
testcontainers.reuse.enable=true
```

**Not suitable for CI** — containers persist between runs, defeating isolation. Reuse is based on container configuration hash; identical configs reuse the same container.

## Configuration

Priority order: environment variables > `~/.testcontainers.properties` > classpath `testcontainers.properties`

Key properties:
```properties
ryuk.container.image=testcontainers/ryuk:0.3.3
ryuk.container.privileged=true
pull.timeout=120
client.ping.timeout=10
docker.host=tcp\://localhost\:2375
docker.tls.verify=1
docker.cert.path=/certs
checks.disable=false
testcontainers.reuse.enable=false
```

Key environment variables:
```bash
DOCKER_HOST=unix:///var/run/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
TESTCONTAINERS_HOST_OVERRIDE=localhost
TESTCONTAINERS_RYUK_DISABLED=true          # Podman, some CI
TESTCONTAINERS_REUSE_ENABLE=true
```

## Modules (50+)

Pre-built modules with correct wait strategies and configuration:
- **Databases**: PostgreSQL, MySQL, MariaDB, MongoDB, Cassandra, Neo4j, Redis, CockroachDB, ClickHouse
- **Messaging**: Kafka, RabbitMQ, Pulsar, ActiveMQ
- **Search/Infra**: Elasticsearch, LocalStack, Vault, Keycloak
- **Testing tools**: Selenium, MockServer, Toxiproxy, WireMock
- **Cloud/Observability**: Dapr, OpenTelemetry Collector, Jaeger

## Supported Languages

Java, Go, .NET, Node.js, Python, Rust, Haskell, Ruby, Clojure, Elixir, PHP, C (native).
