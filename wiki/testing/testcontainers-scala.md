# Testcontainers-Scala

> Sources: testcontainers/testcontainers-scala GitHub, 2026-05-05; testcontainers-scala usage docs, 2026-05-05; yadukrishnan.live integration testing guide, 2026-05-05
> Raw: [testcontainers-scala raw](../../raw/testing/2026-05-05-testcontainers-scala.md)
> Updated: 2026-05-05

## What It Is

Testcontainers-Scala is a Scala wrapper for testcontainers-java. It adds Scala-idiomatic lifecycle traits for ScalaTest and MUnit. Latest: **v0.44.1** (December 2025). MIT. 665 GitHub stars.

Package: `com.dimafeng`. GitHub: `testcontainers/testcontainers-scala`.

## SBT Setup

```scala
val tcVersion = "0.44.1"

libraryDependencies ++= Seq(
  "com.dimafeng" %% "testcontainers-scala-scalatest" % tcVersion % Test,
  // or MUnit:
  "com.dimafeng" %% "testcontainers-scala-munit"     % tcVersion % Test,
  // Add technology modules:
  "com.dimafeng" %% "testcontainers-scala-postgresql" % tcVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-kafka"      % tcVersion % Test,
)

// Mandatory for container cleanup:
Test / fork := true
```

For proper isolation, use integration test configuration:
```scala
lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    IntegrationTest / fork := true,
    libraryDependencies += "com.dimafeng" %% "testcontainers-scala-scalatest" % tcVersion % "it"
  )
// Tests in src/it/scala/, run with: sbt IntegrationTest/test
```

**`fork := true` is mandatory.** Without forking, JVM shutdown hooks (which stop containers) may not fire, leaving orphaned containers.

## Core Abstractions

| Concept | Description |
|---|---|
| `ContainerDef` | Blueprint: how to create and start. Each module has a `.Def(...)` companion. Call `.start()` to get a `Container`. |
| `Container` | Running instance. Exposes service methods: `jdbcUrl`, `mappedPort(n)`, etc. |

## Test Lifecycle Traits (ScalaTest)

All in `com.dimafeng.testcontainers.scalatest`.

### Single Container

```scala
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.{TestContainerForAll, TestContainerForEach}
import org.scalatest.flatspec.AnyFlatSpec

// Container shared across all tests in the suite
class SharedContainerSpec extends AnyFlatSpec with TestContainerForAll {
  override val containerDef = PostgreSQLContainer.Def()

  "test A" should "connect" in withContainers { pg =>
    pg.jdbcUrl   // "jdbc:postgresql://localhost:<port>/test"
    pg.username  // "test"
    pg.password  // "test"
  }
}

// Fresh container per test (full isolation)
class IsolatedContainerSpec extends AnyFlatSpec with TestContainerForEach {
  override val containerDef = PostgreSQLContainer.Def(
    databaseName = "mydb",
    username = "testuser",
    password = "testpass"
  )

  "each test" should "start clean" in withContainers { pg =>
    // fresh container, no leftover state
  }
}
```

### Multiple Containers

```scala
import com.dimafeng.testcontainers.{PostgreSQLContainer, GenericContainer}
import com.dimafeng.testcontainers.scalatest.TestContainersForAll

class MultiContainerSpec extends AnyFlatSpec with TestContainersForAll {
  override type Containers = PostgreSQLContainer and GenericContainer

  override def startContainers(): PostgreSQLContainer and GenericContainer = {
    val pg    = PostgreSQLContainer.Def().start()
    val redis = GenericContainer.Def("redis:7-alpine", exposedPorts = Seq(6379)).start()
    pg and redis
  }

  "test" should "use both" in withContainers { case pg and redis =>
    val jdbcUrl   = pg.jdbcUrl
    val redisPort = redis.mappedPort(6379)
  }
}

// Starts all fresh per test:
class PerTestMulti extends AnyFlatSpec with TestContainersForEach { ... }
```

## Trait Summary

| Trait | Scope | Container Count |
|---|---|---|
| `TestContainerForAll` | Per suite | 1 |
| `TestContainerForEach` | Per test | 1 |
| `TestContainersForAll` | Per suite | N (via `and`) |
| `TestContainersForEach` | Per test | N (via `and`) |
| `ForAllTestContainer` | Per suite | 1 (legacy) |
| `ForEachTestContainer` | Per test | 1 (legacy) |

Legacy traits (`ForAllTestContainer`, `ForEachTestContainer`) use a `container` val directly; modern traits use `containerDef` and `withContainers {}`.

## Lifecycle Hooks

```scala
override def afterContainersStart(containers: Containers): Unit = {
  super.afterContainersStart(containers)   // always call super
  // run Flyway migrations, seed data, configure clients...
}

override def beforeContainersStop(containers: Containers): Unit = {
  super.beforeContainersStop(containers)   // always call super
  // close connections, flush state...
}
```

**Always call `super`** — without it, multi-container teardown may be skipped.

## Container Configuration

```scala
// Constructor params (preferred for common settings):
PostgreSQLContainer.Def(
  dockerImageNameOverride = Some(DockerImageName.parse("postgres:16-alpine")),
  databaseName = "mydb",
  username = "test",
  password = "secret"
)

// Low-level Java API via .configure:
PostgreSQLContainer.Def().configure { c =>
  c.withInitScript("db/schema.sql")   // SQL file on classpath, run at startup
  c.withDatabaseName("testdb")
  c.withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8")
}
```

## GenericContainer

For images without a dedicated module:

```scala
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

// As a Def (with lifecycle traits):
val redisDef = GenericContainer.Def(
  "redis:7-alpine",
  exposedPorts = Seq(6379),
  waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
  env = Map("REDIS_PASSWORD" -> "secret")
)

// Standalone (manual lifecycle):
val container = GenericContainer("nginx:latest", exposedPorts = Seq(80))
container.start()
val port = container.mappedPort(80)
container.stop()
```

## MUnit Integration

```scala
import com.dimafeng.testcontainers.munit.{TestContainersForAll, TestContainersForEach}
import com.dimafeng.testcontainers.PostgreSQLContainer

class MUnitSpec extends munit.FunSuite with TestContainersForAll {
  override type Containers = PostgreSQLContainer
  override def startContainers(): PostgreSQLContainer = PostgreSQLContainer.Def().start()

  test("query works") {
    withContainers { pg => /* use pg */ }
  }
}
```

Functional fixture style:
```scala
val pgFixture = ContainerFunFixture(PostgreSQLContainer.Def())

test("fixture style") {
  pgFixture.test { pg =>
    // pg: PostgreSQLContainer
  }
}
```

## Container-to-Container Networking

When containers must talk to each other, create a Docker network:

```scala
import org.testcontainers.containers.Network

// In startContainers():
val network = Network.newNetwork()

val redis = GenericContainer.Def("redis:7").start()
redis.container.withNetwork(network)
redis.container.withNetworkAliases("redis")

val app = GenericContainer.Def("myapp:latest").start()
app.container.withNetwork(network)
// app connects to redis at "redis:6379" inside Docker (no port collision)
```

Access the underlying Java container via `.container` for network/env configuration.

## DockerComposeContainer

Spin up a full `docker-compose.yml` stack:

```scala
import com.dimafeng.testcontainers.DockerComposeContainer
import org.testcontainers.containers.DockerComposeContainer.ExposedService

val compose = DockerComposeContainer(
  new java.io.File("src/test/resources/docker-compose.yml"),
  exposedServices = List(
    ExposedService("postgres_1", 5432, Wait.forListeningPort()),
    ExposedService("redis_1",    6379)
  )
)

val pgHost = compose.getServiceHost("postgres_1", 5432)
val pgPort = compose.getServicePort("postgres_1", 5432)
```

## Available Modules

Pattern: `"com.dimafeng" %% "testcontainers-scala-<tech>" % version`

| Category | Modules |
|---|---|
| Relational DBs | postgresql, mysql, mariadb, mssqlserver, db2, oracle-free, cratedb, cockroachdb |
| NoSQL | mongodb, cassandra, neo4j, couchbase, orientdb, dynalite, influxdb |
| Messaging | kafka, rabbitmq, pulsar, activemq, activemq-artemis, kafka-schema-registry |
| Cache/Search | elasticsearch, localstack |
| Security/Auth | vault, keycloak |
| Testing Tools | selenium, mockserver, toxiproxy, wiremock, nginx, socat |
| Core | core, scalatest, munit |

## Key Gotchas

- `fork := true` is required — without it, containers may not stop after test suite
- Legacy traits use `val container: Container` (not `def`); modern traits use `containerDef`
- Always call `super` in `afterContainersStart` and `beforeContainersStop`
- Container ports on host are random; always use `mappedPort(n)`, never hardcode
- `TestContainerForAll` (singular) vs `TestContainersForAll` (plural): singular = 1 container, plural = N
- MUnit: import from `com.dimafeng.testcontainers.munit`, not `scalatest`
- Network configuration must be applied before `container.start()` — set on the `.container` underlying object

## See Also

- [Testcontainers Overview](testcontainers-overview.md)
- [Dapr Testcontainers](../dapr/dapr-testcontainers.md)
