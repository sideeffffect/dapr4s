# dapr4s

| CI | Release |
| --- | --- |
| [![Build Status][Badge-GitHubActions]][Link-GitHubActions] | [![Release Artifacts][Badge-MavenCentral]][Link-MavenCentral] |

A Scala 3 library that exposes every [Dapr](https://dapr.io) building block as a
**capture-checked capability**. Dapr effects — state, pub/sub, service invocation,
secrets, configuration, bindings, distributed locks, actors, workflows,
cryptography, jobs, and conversation (LLM) — are
modelled as `scala.caps.Capability` values that the compiler tracks. User code
compiles under `import language.experimental.safe`, so the compiler statically
guarantees that a Dapr resource can never escape the scope that owns it. The Dapr
SDKs (Java SDK on the JVM, `@dapr/dapr` on Scala.js) are hidden entirely; users
see only Scala types.

## Requirements

- Scala `3.10.0-RC1-…-NIGHTLY` (capture checking / safe mode; exact version pinned in `project.scala`)
- [scala-cli](https://scala-cli.virtuslab.org/) `>= 1.13.0` (older versions cannot build the Scala.js platform; `>= 1.14` to run the Scala.js integration harness, `scripts/test-js-integration.sh`)
- JVM 25 (for the JVM platform)
- For Scala.js builds: `npm ci` + `scripts/generate-st-facades.sh` (once per machine — see below);
  Node 25+ to run capability-touching code
- Docker (only for the integration tests, which exercise a real `daprd` sidecar + Redis)

## Build & test

Sources live in `src/{shared,jvm,js}` and `test/{shared,jvm,js}` (platform-specific files also
carry a per-file `//> using target.platform` directive). Platform-specific *dependencies* live in
`jvm-deps.scala` / `jvm-test-deps.test.scala` / `js-deps.scala`, each scoped by its own
`target.platform` directive — so plain `--js` invocations just work, with no `--exclude` flags
for dependency scoping.

```bash
scala-cli compile .                                       # JVM
scala-cli test . --test-only 'dapr4s.test.unit.*'         # JVM unit tests
scala-cli test . --test-only 'dapr4s.test.integration.*'  # JVM integration tests (needs Docker)

scripts/generate-st-facades.sh                            # once per machine, before any --js build
scala-cli compile --js .                                  # Scala.js
scala-cli test --js . --exclude test/js/integration --test-only 'dapr4s.test.unit.*'   # Scala.js unit tests
PATH=<node25-bin>:$PATH scripts/test-js-integration.sh    # Scala.js integration tests (Docker + Node 25)
```

The one remaining `--exclude` (of `test/js/integration`) exists only because those Wasm-only
suites use orphan `js.await`, which wedges the plain-JS linker; the integration script runs
them on the Wasm backend instead, against a real `daprd` 1.17 + Redis environment.

## Usage

```scala
//> using dep "com.github.sideeffffect::dapr4s::<version>"
```

(The `::` before the version resolves the right platform artifact — `dapr4s_3` on
the JVM, `dapr4s_sjs1_3` on Scala.js.)

```scala
import dapr4s.*

DaprCapability.state(StateStoreName("statestore")):   // StateCapability^{cap} in scope
  StateCapability.save(key, value)
// Using StateCapability here — outside the block — is a compile error.
```

## Platforms

The public API is identical on the JVM and on Scala.js — synchronous, direct style
on both. On the JVM, blocking calls park virtual threads; on Scala.js, the same
calls suspend the WebAssembly stack via JSPI (JavaScript Promise Integration)
while the Node event loop keeps running.

**Scala.js supports the full capability matrix** — state, pub/sub, invocation,
bindings, secrets, configuration, locks, crypto, actors, workflows, and
`serve()` with full app-channel parity (subscriptions, invoke routes, input
bindings, job routes, actor hosting, workflow hosting) — **except `jobs` and
`conversation`**, which the Dapr JS SDK has no API for. On Scala.js those two
methods simply don't exist at compile time (they live on a JVM-only platform
trait), so using them is a compile error, not a runtime exception — use the JVM
platform for those.

JS consumers of capabilities must link with the experimental WebAssembly backend
and run on Node 25+ (or Node 23/24 with `--experimental-wasm-jspi`); the pure
parts of dapr4s (models, codecs, derivation) also link on the plain JS backend:

```scala
//> using platform "scala-js"
//> using jsEmitWasm true
//> using jsModuleKind "es"
//> using jsEsVersionStr "es2017"
//> using dep "com.github.sideeffffect::dapr4s::<version>"
```

Run `npm install @dapr/dapr` so the SDK is resolvable from the directory Node
executes in, then enter `js.async { ... }` once at the program edge (or use the
JS-only `runAsync`/`serveAsync`, which wrap it for you):

```scala
import dapr4s.*
import scala.scalajs.js

// one-shot request/response, with the single js.async entry at the program edge:
def main(args: Array[String]): Unit =
  js.async {
    Dapr().run:
      summon[DaprCapability].state(StateStoreName("statestore")).get(StateStoreKey("k"))
  }: Unit
```

### Scala.js facades: a one-time local generation step

The `dapr4s_sjs1_3` POM references ScalablyTyped-generated facades of `@dapr/dapr`
(`org.scalablytyped::dapr__dapr` and friends, see `js-deps.scala`). These are **not on Maven
Central** — they live only in the local ivy repository (`~/.ivy2/local`), which scala-cli resolves
out of the box. Before your first build against dapr4s JS (and in CI), materialise them locally:

1. get this repository's `package.json` + `package-lock.json` + `scripts/generate-st-facades.sh`
   (all committed here — the converter inputs are `@dapr/dapr`, `@types/express`, `@types/node`,
   with `typescript` needed by the converter itself),
2. run `npm ci`, then `scripts/generate-st-facades.sh`.

The facade digests are deterministic in (package-lock.json, converter version, converter flags),
so the script reproduces exactly the coordinates the published POM references. It is idempotent
and skips instantly when the jars already exist.

See [`DESIGN.md`](docs/DESIGN.md) for the architecture, the two-layer
(safe / `@assumeSafe` shell) model, and the Scala.js platform details, and
[dapr4s-examples](https://github.com/sideeffffect/dapr4s-examples) for runnable examples.

## Sponsors

This work has been sponsored by [Chili Piper](https://github.com/Chili-Piper).

## License

Apache-2.0

[Link-GitHubActions]: https://github.com/sideeffffect/dapr4s/actions/workflows/ci.yml?query=branch%3Amaster "GitHub Actions link"
[Badge-GitHubActions]: https://github.com/sideeffffect/dapr4s/actions/workflows/ci.yml/badge.svg?branch=master "GitHub Actions badge"
[Link-MavenCentral]: https://repo1.maven.org/maven2/com/github/sideeffffect/dapr4s_3/ "Maven Central link"
[Badge-MavenCentral]: https://maven-badges.sml.io/sonatype-central/com.github.sideeffffect/dapr4s_3/badge.svg "Maven Central badge"
