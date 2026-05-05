# Testcontainers-Scala

> Source: https://github.com/testcontainers/testcontainers-scala ; https://raw.githubusercontent.com/testcontainers/testcontainers-scala/master/docs/src/main/tut/usage.md ; https://raw.githubusercontent.com/testcontainers/testcontainers-scala/master/docs/src/main/tut/setup.md ; https://yadukrishnan.live/easy-integration-testing-with-testcontainer-scala ; https://testcontainers.github.io/testcontainers-scala/
> Collected: 2026-05-05
> Published: Unknown

## What Is It

Testcontainers-Scala is a Scala wrapper for testcontainers-java. It provides Scala-idiomatic traits for ScalaTest and MUnit to manage Docker container lifecycles in tests. 665 GitHub stars. Latest version: v0.44.1 (December 2025). MIT licensed. Maintained by dimafeng and community.

## SBT Setup

```scala
// ScalaTest
libraryDependencies += "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.44.1" % "it,test"
// MUnit
libraryDependencies += "com.dimafeng" %% "testcontainers-scala-munit" % "0.44.1" % "it,test"
// Add specific modules (e.g., PostgreSQL):
libraryDependencies += "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1" % "it,test"
```

Recommended: use SBT integration test configuration:
```scala
lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    IntegrationTest / fork := true,   // essential for clean JVM shutdown/container stop
    libraryDependencies += "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.44.1" % "it"
  )
// Run with: sbt IntegrationTest/test
// Place tests in src/it/scala/
```

Fork is essential: without it, JVM shutdown hooks (which stop containers) may not fire, leaving containers running.

## Core Abstractions

- **`ContainerDef`** — blueprint / definition (how to build and start). Has a `start()` method that returns a running `Container`. Created via companion `.Def(...)` pattern.
- **`Container`** — a running instance. Exposes service-specific methods (`jdbcUrl`, `username`, `getGrpcPort`, etc.).

## Test Lifecycle Traits (ScalaTest)

All traits in `com.dimafeng.testcontainers.scalatest`.

### Single Container — Modern API

```scala
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.flatspec.AnyFlatSpec

// Start once per suite
class MySpec extends AnyFlatSpec with TestContainerForAll {
  override val containerDef = PostgreSQLContainer.Def()

  "query" should "work" in withContainers { pg =>
    // pg: PostgreSQLContainer (running)
    val url = pg.jdbcUrl   // "jdbc:postgresql://localhost:PORT/test"
    val user = pg.username
    val pass = pg.password
  }
}

// Start fresh per test (isolation)
class MySpec extends AnyFlatSpec with TestContainerForEach {
  override val containerDef = PostgreSQLContainer.Def(
    databaseName = "mydb",
    username = "testuser",
    password = "testpass"
  )

  "test" should "have clean state" in withContainers { pg =>
    // fresh container for this test only
  }
}
```

### Multiple Containers — Modern API

```scala
import com.dimafeng.testcontainers.{PostgreSQLContainer, GenericContainer}
import com.dimafeng.testcontainers.scalatest.TestContainersForAll

class MultiSpec extends AnyFlatSpec with TestContainersForAll {
  override type Containers = PostgreSQLContainer and GenericContainer

  override def startContainers(): PostgreSQLContainer and GenericContainer = {
    val pg   = PostgreSQLContainer.Def().start()
    val redis = GenericContainer.Def("redis:7-alpine", exposedPorts = Seq(6379)).start()
    pg and redis
  }

  "multi-container test" should "work" in withContainers { case pg and redis =>
    val pgUrl = pg.jdbcUrl
    val redisPort = redis.mappedPort(6379)
  }
}
```

## Legacy Single-Container API

```scala
// ForAllTestContainer — one container for all tests
class LegacySpec extends AnyFlatSpec with ForAllTestContainer {
  override val container: PostgreSQLContainer = PostgreSQLContainer()
  // container.jdbcUrl, etc.
}

// ForEachTestContainer — one container per test
class LegacyEach extends AnyFlatSpec with ForEachTestContainer {
  override val container: PostgreSQLContainer = PostgreSQLContainer()
}
```

Legacy classes from `com.dimafeng.testcontainers` (not `.scalatest`).

## Container Configuration

```scala
// Simple: use Def constructor params
PostgreSQLContainer.Def(
  dockerImageNameOverride = Some(DockerImageName.parse("postgres:16")),
  databaseName = "mydb",
  username = "test",
  password = "test"
)

// Low-level Java API access via .configure
PostgreSQLContainer.Def().configure { c =>
  c.withInitScript("schema.sql")       // SQL file on classpath
  c.withDatabaseName("testdb")
  c.withEnv("MY_VAR", "value")
}
```

## GenericContainer

For any Docker image not covered by a dedicated module:

```scala
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

// Via Def (recommended with lifecycle traits)
val redisDef = GenericContainer.Def(
  "redis:7-alpine",
  exposedPorts = Seq(6379),
  waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
  env = Map("REDIS_PASSWORD" -> "secret")
)

// Direct instantiation
val container = GenericContainer(
  "nginx:latest",
  exposedPorts = Seq(80),
  waitStrategy = Wait.forHttp("/")
)
container.start()
val port = container.mappedPort(80)
container.stop()
```

## Lifecycle Hooks

```scala
override def afterContainersStart(containers: Containers): Unit = {
  super.afterContainersStart(containers)   // MUST call super
  // run migrations, seed data, configure app, etc.
}

override def beforeContainersStop(containers: Containers): Unit = {
  super.beforeContainersStop(containers)   // MUST call super
  // cleanup connections, etc.
}
```

Always call `super` — the multi-container machinery depends on it.

## MUnit Integration

```scala
import com.dimafeng.testcontainers.munit.TestContainersForAll
import com.dimafeng.testcontainers.PostgreSQLContainer

class MyMUnitSpec extends munit.FunSuite with TestContainersForAll {
  override type Containers = PostgreSQLContainer

  override def startContainers(): PostgreSQLContainer =
    PostgreSQLContainer.Def().start()

  test("query works") {
    withContainers { pg =>
      // pg: PostgreSQLContainer
    }
  }
}
```

### ContainerFunFixture (MUnit functional style)

```scala
val pgFixture = ContainerFunFixture(PostgreSQLContainer.Def())

test("with fixture") {
  pgFixture.test { pg =>
    // pg available here
  }
}
```

## Available Modules (40+)

Pattern: `testcontainers-scala-<tech>` artifact in `com.dimafeng` group.

**Databases**: postgresql, mysql, mariadb, mongodb, cassandra, neo4j, couchbase, clickhouse, oracle-free, presto, mssqlserver, db2, cratedb, cockroachdb, dynalite, orientdb, influxdb

**Messaging**: kafka, rabbitmq, pulsar, activemq, activemq-artemis

**Search/Cache**: elasticsearch, localstack

**Tools/Infrastructure**: nginx, selenium, mockserver, toxiproxy, vault, keycloak, socat, wiremock, kafka-schema-registry

**Core**: core (GenericContainer, DockerComposeContainer), scalatest, munit

## DockerComposeContainer

Spin up multiple services defined in a `docker-compose.yml`:

```scala
DockerComposeContainer(
  new java.io.File("docker-compose.yml"),
  exposedServices = List(
    ExposedService("postgres", 5432, Wait.forListeningPort()),
    ExposedService("redis", 6379)
  )
)
```

Access services:
```scala
container.getServiceHost("postgres", 5432)
container.getServicePort("postgres", 5432)
```

## Networking (Container-to-Container)

For containers that need to talk to each other, use a shared Docker network:

```scala
import org.testcontainers.containers.Network

// In startContainers():
val network = Network.newNetwork()

val redis = GenericContainer.Def("redis:7").start()
redis.container.withNetwork(network)
redis.container.withNetworkAliases("redis")

val app = GenericContainer.Def("myapp:latest").start()
app.container.withNetwork(network)
// app can reach redis at "redis:6379" inside Docker network
```

Note: testcontainers-scala wraps the Java container; access via `.container` for network methods.

## Key Gotchas

- `fork := true` is mandatory in SBT for container cleanup
- `container` val (not `def`) in legacy traits — always `val`
- Always call `super` in lifecycle hooks
- Container ports are random on host; use `mappedPort(n)` not hardcoded ports
- `TestContainerForAll` vs `TestContainersForAll`: singular = one container, plural = multiple
- MUnit: import from `com.dimafeng.testcontainers.munit`, not `.scalatest`
