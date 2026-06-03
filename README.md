# dapr4s

A Scala 3 library that exposes every [Dapr](https://dapr.io) building block as a
**capture-checked capability**. Dapr effects — state, pub/sub, service invocation,
secrets, configuration, bindings, distributed locks, actors, workflows,
cryptography, jobs, and conversation (LLM) — are
modelled as `scala.caps.Capability` values that the compiler tracks. User code
compiles under `import language.experimental.safe`, so the compiler statically
guarantees that a Dapr resource can never escape the scope that owns it. The Dapr
Java SDK is hidden entirely; users see only Scala types.

## Requirements

- Scala `3.9.0-RC1-…-NIGHTLY` (capture checking / safe mode; see `project.scala`)
- JVM 25
- [scala-cli](https://scala-cli.virtuslab.org/)
- Docker (for the integration tests, which spin up a real `daprd` sidecar + Redis
  via testcontainers)

## Build & test

```bash
scala-cli compile .
scala-cli test . --test-only 'dapr4s.test.unit.*'         # unit tests
scala-cli test . --test-only 'dapr4s.test.integration.*'  # needs Docker
```

## Usage

```scala
//> using dep "com.github.sideeffffect::dapr4s:<version>"
```

```scala
import dapr4s.*

DaprCapability.state(StoreName("statestore")):   // StateCapability^{cap} in scope
  StateCapability.save(key, value)
// Using StateCapability here — outside the block — is a compile error.
```

See [`DESIGN.md`](DESIGN.md) for the architecture and the two-layer
(safe / `@assumeSafe` shell) model, and
[dapr4s-examples](https://github.com/sideeffffect/dapr4s-examples) for runnable examples.

## License

Apache-2.0
