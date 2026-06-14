# AGENTS.md — dapr4s

## What this project is

A Scala 3 library that exposes every DAPR building block (state, pub/sub, service invocation,
secrets, configuration, output bindings, distributed lock) as a **tracked capability** in the
sense of Scala 3 capture checking. User code compiles under
`import language.experimental.captureChecking` and `import language.experimental.safe`.
The DAPR Java SDK is completely hidden — library users see only Scala types, opaque wrappers,
and capability traits. No `Mono`, no `CompletableFuture`, no Java imports, nothing.

The central design idea: each DAPR effect is a `scala.caps.Capability` subtype, and the Scala 3
compiler statically verifies which effects a computation may perform and that resources cannot
escape their managed scope.

Refer to `docs/DESIGN.md` for the full architecture and to `docs/SPEC.allium` for the formal specification.
Both must stay in sync with the code at all times.

---

## Scala stack

- **Scala version**: latest nightly (`3.9.0-RC1-bin-*-NIGHTLY` or later). Always use the newest
  available nightly to get the latest capture-checking and safe-mode fixes. Update when the build
  tool hints that a newer nightly is available.
- **Platforms**: JVM **and Scala.js** (`//> using platform "jvm" "scala-js"`; jvm first = default).
  Plain `scala-cli compile/test .` builds the JVM platform; Scala.js invocations just add `--js`
  (JVM-only deps live in `jvm-deps.scala`/`jvm-test-deps.test.scala`, JS-only deps in
  `js-deps.scala` — each scoped to its platform by a `target.platform` directive, which is what
  keeps the `_sjs1_3` build/POM clean; no `--exclude` flags are needed for dependency scoping):
  - `scala-cli compile --js .`
  - `scala-cli test --js . --exclude test/js/integration --test-only 'dapr4s.test.unit.*'`
  (That `--exclude` is the only one in the build — the Wasm-only integration suites contain
  orphan `js.await`, which *wedges* the plain-JS linker instead of erroring; see Testing.)
- **Directory layout**: `src/{shared,jvm,js}` and `test/{shared,jvm,js}`. The layout is for
  humans — scala-cli has no platform directory convention, so every file under a `jvm/`/`js/`
  directory ALSO carries its own per-file `//> using target.platform "jvm"`/`"scala-js"`
  directive (the directive is what scopes it). Packages are unchanged by the layout
  (`src/jvm/internal/` and `src/js/internal/` are both `dapr4s.internal`).
  `//> using jsEsVersionStr "es2017"` is required by `js.async`/`js.await`.
  **JS build prerequisite**: the ScalablyTyped facade jars referenced by `js-deps.scala` live only
  in the local ivy repository — run `scripts/generate-st-facades.sh` once per machine (and again
  whenever the pinned digests change) before the first `--js` build, or resolution fails. This is
  a requirement for building dapr4s only: the published `dapr4s_sjs1_3` jar embeds the facade
  classes (`scripts/embed-st-facades.sh`), so consumers resolve everything from Maven Central.
- **Build tool**: Scala CLI (`project.scala` using directives). **scala-cli >= 1.13.0 is required
  for the Scala.js build** (munit 1.3.0 JS needs Scala.js IR 1.21; the JS integration harness
  wants >= 1.14). Run unit tests with `scala-cli test . --test-only 'dapr4s.test.unit.*'`.
- **JVM**: Zulu 25 (`//> using jvm "zulu:25.0.3"` or later). JDK 25 is required for stable
  virtual thread support (no carrier pinning on `synchronized`).
- **Compiler flags** (all active):
  - `-language:experimental.captureChecking`
  - `-language:experimental.pureFunctions` — `A => B` is a pure function; context functions are
    pure by default; strengthens `Dapr.run`'s body type.
  - `-experimental` — enables clause interleaving (`def f[A](x: A)[B: TC]: B`).
  - `-Ycc-verbose`, `-Yexplicit-nulls`, `-Wconf:any:error` (fatal warnings).
- **Dependencies**: upickle 3.3.1 (pinned — 4.x crashes on CC-annotated types, test-only),
  munit 1.3.0, testcontainers-scala-munit 0.43.6, testcontainers-dapr 1.17.2 (all test-only —
  not part of the published library). Cross deps use the `::version` (double-colon) form;
  `scala-java-time` provides `java.time` on Scala.js (a thin JDK shim on the JVM). Platform
  scoping: the Dapr Java SDK lives in `jvm-deps.scala`; testcontainers in
  `jvm-test-deps.test.scala` (plain `using dep` + the `.test.scala` suffix for test scope —
  deliberately NOT `test.dep`, which is not platform-scoped and would leak into the JS test
  build); the ScalablyTyped facade coordinates in `js-deps.scala`. The JS layer's npm deps
  (`@dapr/dapr`, `@types/express`, `@types/node`, `typescript`) are declared in `package.json`
  (with `package-lock.json` committed — the ScalablyTyped digests are deterministic in it).

---

## Code style and correctness rules

### Escape hatches from the type system must be documented

Every use of a CC escape hatch or type-system workaround must be accompanied by a thorough
comment explaining:

1. **WHAT** the escape hatch is (`asInstanceOf`, `@scala.caps.assumeSafe`, `try/catch`-rethrow
   for CanThrow isolation, `AnyRef` storage to erase capture sets, etc.)
2. **WHY** it is necessary — the specific compiler or CC constraint that forces the workaround
3. **WHY IT IS SAFE** — the invariant or contract that ensures the runtime behaviour is correct
   even though the type system cannot verify it
4. **WHERE TO LOOK** for the canonical example or further explanation

Examples of mandatory documentation:
- `@scala.caps.assumeSafe` on a class: explain why the class must be trusted and what safety
  invariant it maintains internally.
- `fn.asInstanceOf[AnyRef]` storing a capturing lambda: explain that the lambda is later cast
  back to its runtime type under `@assumeSafe` and the cast is safe because the lambda type is
  preserved erased in a typed HashMap key-value contract.
- `try { ... } catch case e: Exception => throw e` in a lambda: explain the CC sibling-lambda
  CanThrow isolation requirement (see AGENTS.md / CC section below).
- `handler.asInstanceOf[Req => Resp]` (if ever necessary): explain why CC cannot track the
  capture and why the underlying semantics guarantee type safety.

No escape hatch should ever be left "obvious" or undocumented. If you find one without a comment,
add the comment before moving on.

### Never catch fatal exceptions
Use `scala.util.control.NonFatal` everywhere a broad catch is needed. Fatal exceptions
(`OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, `LinkageError`, `ControlThrowable`)
must never be caught — they must propagate immediately.

The one explicit exception: `InterruptedException` may be caught when needed to restore the
interrupt flag before rethrowing. This must be argued and documented thoroughly in a block
comment explaining (a) why the flag must be restored, (b) why we do not wrap it, and (c) why
this is propagation not suppression. See `MonoOps.awaitResult()` for the canonical example.

### Virtual threads — bridging Reactor to the calling thread
The DAPR Java SDK returns `Mono<T>` for everything. We bridge to blocking via
`mono.toFuture().get()` (not `Mono.block()`). Reason: `CompletableFuture.complete()` uses CAS
with no `synchronized` in the hot path, so neither the calling virtual thread nor the gRPC
delivery thread risks carrier pinning. `Mono.block()`'s internal `BlockingSingleSubscriber`
declares `onNext`/`onComplete`/`onError` as `synchronized`, which can briefly pin the carrier
of the gRPC worker thread on JDK < 24 under high concurrency. This bridge lives in
`internal/MonoOps.scala` as the `awaitResult()` extension method.

For best throughput, callers should invoke `Dapr(...).run` from a virtual thread:
- Plain Scala `main()`: `Thread.ofVirtual().start(() => Dapr(config).run { ... }).join()`
- Spring Boot 3.2+: `spring.threads.virtual.enabled=true`
- Quarkus: `@RunOnVirtualThread`
- Helidon 4: virtual threads by default

### Comments
Only write comments when the *why* is non-obvious: a hidden constraint, a subtle invariant, a
workaround for a specific library bug, or behavior that would surprise a reader. When something
warrants a comment, make it thorough — explain the reasoning fully, not just the action. See the
`InterruptedException` handling in `MonoOps` and the `NonFatal` trade-off note in `Dapr`
as style references.

### CC sibling-lambda CanThrow pattern

In Scala 3.9 CC, each lambda that calls a `throws`-annotated method creates a fresh anonymous
`CanThrow` capability tagged to that specific lambda. Sibling lambdas (defined in the same method
body) cannot share these capabilities — a fundamental CC constraint that prevents CanThrow from
"leaking" across unrelated closures.

**Symptom**: compiler error "capability `any` cannot flow into capture set {any²}" when two or
more lambdas in the same method each call a throwing method.

**Required pattern** for every handler lambda that calls throwing methods:
```scala
handlers.onInvoke[Req](InvokeMethodName("my-method"))[Resp] { req =>
  try myHandlerMethod(req)          // declares throws Exception
  catch case e: Exception => throw e  // re-throw WITHOUT swallowing
}
```

The `try/catch` absorbs each lambda's CanThrow requirement at that lambda's boundary, so the
next sibling lambda starts with a fresh context.

**Why `throw e` instead of a meaningful handler**: the lambda is a thin dispatcher that
intentionally lets exceptions propagate to the calling runtime (DaprAppServer or TestDaprApp),
which has its own error handling.  Swallowing exceptions here would hide Dapr client errors.

**Why `import unsafeExceptions.canThrowAny` is also needed**: in Scala 3.9, rethrowing inside a
catch clause (`throw e`) still requires a `CanThrow[Exception]` in scope.  The top-level import
provides this for the whole file.

### Experimental Scala features — exploit them, don't just enable them
- **`pureFunctions`**: `Dapr.run`/`Dapr.serve` take a `DaprCapability ?=> T` body — a pure context
  function. Demonstrate this with tests that show pure lambdas compose correctly and the body
  cannot close over external capabilities.
- **Clause interleaving**: `invoke` methods use `def invoke[Req: JsonCodec](...)[Resp: JsonCodec]`
  so `Req` is inferred from the data argument and `Resp` is specified at the call site.

### Scala type-safety and coding best practices

The wiki's `scala-type-safety/` section is the primary reference for Scala coding conventions in this project. Consult it before writing new code and after finding a style issue. Key articles:

- **`wiki/scala-type-safety/scala-best-practices-nrinaudo.md`** — comprehensive rule set: sealed types must have `final` subtypes, case classes must be `final`, avoid unsafe partial ops (`head`/`get`/`reduce` on possibly-empty collections → use `headOption`/`getOrElse`/`reduceOption`), avoid `null` (use `Option`), prefer `sealed abstract class` over `sealed trait` for ADT root types, add explicit types to all public members, always use `override`.

- **`wiki/scala-type-safety/primitive-obsession-opaque-types.md`** — use opaque types for every domain string/int value (e.g. `StateStoreName`, `PubSubName`). Zero-cost at runtime. Smart constructors validate at the boundary; downstream code trusts the type. Prefer opaque types over `case class` wrappers (no boxing) or `AnyVal` (allocates in generic contexts).

- **`wiki/scala-type-safety/parse-dont-validate.md`** — encode validation results in the type; don't validate-and-discard. Parse at system boundaries; downstream code uses well-typed values.

- **`wiki/scala-type-safety/adts-illegal-states.md`** — data ADTs with nullable-field anti-pattern, `final case object`, using Scala 3 `enum` instead of `scala.Enumeration`, declaring constructors in companion objects.

Specific rules currently active in this codebase:
- All case classes and case objects are `final`.
- All subtypes of sealed types are `final`.
- `UnlockStatus` and `SubscriptionResult` are `enum` (not `case class` with int codes).
- `StateOp` root is `sealed abstract class` (not `sealed trait`) — proper ADT root.
- Do not call `.head`, `.tail`, `.last`, `.get` on collections or `Option`/`Try`/`Either` without a prior length/presence check; use `headOption`, `getOrElse`, `getOrElse(fail(...))` in tests.
- Domain identifiers are split per Dapr building block — **never** unify a name/key type across two building blocks just because both are `String` underneath. The test: if a doc comment would have to say "X *or* Y", it's two types. Current splits to preserve (do not re-merge):
  - **Method names**: `InvokeMethodName` for service invocation (`InvokeCapability.invoke`, `InvokeRoute`, `InvokeRequest`) vs. `ActorMethodName` for actor methods (`ActorCapability.invoke`/`invokeVoid`, `ActorMethodRoute`) — an HTTP route on a remote app vs. a method on a stateful actor. (Actor timer/reminder callbacks use `TimerName`/`ReminderName`, not a method-name type.)
  - **Store names**: `StateStoreName` (`DaprCapability.state`) vs. `LockStoreName` (`DaprCapability.lock`) — distinct Dapr components with distinct YAML. (Mirrors the existing `ConfigurationStoreName`/`SecretStoreName` split.)
  - **State keys**: `StateStoreKey` (app-level `StateCapability`) vs. `ActorStateKey` (per-instance `ActorContext`/`ActorState`).

### SDK interop boundary (Java on JVM, @dapr/dapr on JS)
Everything in the two `internal/` trees is marked `@scala.caps.assumeSafe`. There are two
platform walls behind the same boundary:

- `src/jvm/internal/` (all jvm-tagged) is the **JVM wall**: the only place Java SDK types may
  appear. Nothing from the Java SDK (`Mono`, `DaprClient`, `GrpcChannel`, proto classes, etc.)
  may appear in `src/` files outside it or in any test file.
- `src/js/internal/` (all js-tagged, same package `dapr4s.internal`) is the **JS wall**: the only
  place `@dapr/dapr` (and express/Node) types may appear. Those types are the
  **ScalablyTyped-generated facades** (`dapr4styped.daprDapr`, `dapr4styped.expressServeStaticCore`,
  `dapr4styped.node`, ... — generated into the dapr4s-specific `dapr4styped.*` root package, see
  js-deps.scala), plus the single surviving hand-written shim in
  `dapr4s.internal.facade` (`src/js/internal/facade/ExpressModule.scala`). No `js.Promise`,
  `dapr4styped.*` type, or other JS interop type may leak into the public API. Two deliberate
  carve-outs mirror the JVM side (where `src/jvm/Dapr.scala` constructs Java SDK clients): the
  platform `Dapr` entry points (`src/jvm/Dapr.scala`, `src/js/Dapr.scala`) may construct SDK
  clients to hand to the internal layer, and the JS-only `runAsync`/`serveAsync` conveniences
  intentionally return `js.Promise` at the program edge — no other public member may.

The `@assumeSafe` boundary is the wall on both platforms — same rule, same documentation duty
(WHAT/WHY/WHY SAFE on every escape hatch).

### Platform-diverging public surface: the platform-trait technique

When a building block exists in only one platform's SDK, **never** stub it with
`UnsupportedOperationException` — make it not exist on the other platform at compile time.
THE pattern (jobs/conversation are the worked example):

- The shared trait inherits a platform parent trait:
  `trait DaprCapability extends ..., DaprCapabilityPlatform` and
  `object DaprCapability extends DaprCapabilityCompanionPlatform` (in `src/shared/`).
- `src/jvm/DaprCapabilityPlatform.scala` contributes the JVM-only members (`jobs`,
  `conversation` + the companion transformer twins); the `src/js/` twin declares the same
  trait names but **empty**. WHY traits and not a per-platform `DaprCapability` file: the
  companion must sit in the same file as the trait, so the trait/companion pair cannot be
  forked per platform without duplicating the whole shared surface.
- Everything reachable only from that surface moves wholly under `src/jvm/`:
  `JobsCapability`/`ConversationCapability`, their models (`JobsModels.scala`,
  `ConversationModels.scala`), their impls, the `Jobs.derive` engine
  (`src/jvm/derivation/Jobs.scala`), and the jobs runtime forwarders
  (`Forwarders extends ForwardersPlatform`; `Forwarders.jobRoute` stays shared because the
  inbound job-trigger side is cross-platform).
- On the JVM platform trait, keep the `^{this}` return types working via a self-type
  (`this: DaprCapability =>`) so CC tracks the same capability instance as the shared trait.

Result: `DaprCapability.jobs` on Scala.js is "value jobs is not a member" at compile time, and
the `_sjs1_3` artifact carries no jobs/conversation API at all.

### Scala.js layer rules

- **Orphan `js.await` ONLY via `JsAwait`** (`src/js/internal/JsAwait.scala`) — the single home of
  the `scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait` import. Never import it anywhere else.
- **Every callback invoked from a JS frame re-enters `js.async`** before touching dapr4s code
  (express handlers, SDK activity executors, promise reactions). JSPI suspension cannot cross a
  JavaScript stack frame — skipping this throws `WebAssembly.SuspendError` at runtime.
- **Facades are ScalablyTyped-generated**, not hand-written. `scripts/generate-st-facades.sh`
  converts the npm packages pinned in `package.json` into `dapr4styped.*` jars in `~/.ivy2/local`
  (run it once per machine; idempotent, fast skip when the jars exist). Facade imports use the
  **`dapr4styped.*` root package** (`--outputPackage dapr4styped`), never ST's default
  `typings.*` — the classes ship inside the published jar and must not collide with a consumer's
  own ST generation. `js-deps.scala` pins the resulting
  `org.scalablytyped::<name>::<npmVersion>-<digest>` coordinates as **`compileOnly.dep`** (they
  are on the compile classpath and platform-scoped like `using dep`, but never enter the
  published POM); the digests are deterministic in (package-lock.json, converter version,
  converter flags), and the script and `js-deps.scala` must name the same digests — the script
  fails loudly on drift. The converter tuple (version `1.0.0-beta45`,
  `--scala 3.3.6 --scalajs 1.21.0 -s es2022 --outputPackage dapr4styped`) is THE pin: changing
  any element changes the digests. The ST jars are precompiled with their own flags, so our
  `-Wconf:any:error`/CC flags do not apply to them — but OUR code must still compile with zero
  warnings, and explicit-nulls means ST results must NOT be `.nn`-ed (it is an error: unnecessary
  `.nn`).
- **The published `dapr4s_sjs1_3` jar is self-contained**: at publish time
  `scripts/embed-st-facades.sh` resolves the exact transitive `org.scalablytyped` jar set of the
  three facade roots (via coursier, from the js-deps.scala pins — never by globbing the ivy
  directory, which accumulates stale digests) and stages their class/tasty/sjsir entries in
  `.scala-build/st-embed`; the JS publish then runs with `--resource-dirs .scala-build/st-embed`.
  Consumers resolve dapr4s from Maven Central alone (the POM carries scalablytyped-runtime +
  scalajs-dom as regular deps for the embedded facades). Generation remains a prerequisite for
  BUILDING dapr4s itself only.
- **The one hand-written exception**: `src/js/internal/facade/ExpressModule.scala`, the express
  default-import shim (ST's namespace-import entry point is not callable under Node ESM, and
  `express.text` lost its type to a converter warning). Anything else ST genuinely cannot express
  must live there too, justified in its header; everything else uses `dapr4styped.*` directly.
- **The ST types ARE the signatures; verify runtime behaviour against `node_modules` sources.**
  TypeScript types are erased — and occasionally wrong (`SubscribeConfigurationStream.stop()`
  returns a Promise despite `: void`; transaction etags go on the wire as plain strings despite
  `IEtag = {value}`) — so behavioural claims (soft-failure shapes, falsy-body bugs, enum wire
  values) still come from reading the installed `@dapr/dapr`/`express` JS sources (record findings
  in `wiki/dapr/dapr-js-sdk.md`). Where the ST type and the verified runtime diverge, keep the
  runtime behaviour and document the divergence at the cast (WHAT/WHY/WHY SAFE).
- **Never reference a deep-module ST object in value position for `@dapr/dapr`.** ScalablyTyped's
  deep-module specifiers (e.g. `@dapr/dapr/enum/HttpMethod.enum`) carry no `.js` extension and the
  package has no `exports` map, so Node ESM (the Wasm/JSPI production target) throws
  `ERR_MODULE_NOT_FOUND` at load time. Deep **types** are fine (erased, no import emitted); for
  **values** use the `dapr4styped.daprDapr.mod.*` root re-exports, and where none exists (e.g.
  `LockStatus`) pin the runtime values with a documented source reference. Runtime-verified by the
  e2e smoke run — compile-green is not enough to catch this.
- SDK gotchas (still true under ST): **ports are strings** everywhere in the JS SDK;
  **`CommunicationProtocolEnum` is numeric with `GRPC = 0`, `HTTP = 1`** — defaulting to 0
  silently picks gRPC. `HttpMethod` values are lowercase strings. Options objects are
  `Partial<...>` — ST renders them as builder-style traits (`PartialDaprClientOptions().setX(...)`).

---

## Formatting

The project uses scalafmt. Config is in `.scalafmt.conf` (version 3.10.4, dialect `scala3`,
`maxColumn = 120`, trailing commas everywhere).

Run the formatter: `scala-cli fmt .`
Check without writing: `scala-cli fmt --check .`

**Always format before committing.** The CI `format` job runs `scala-cli fmt --check .` and
blocks the build (and `publish`) on any unformatted file, so a missed format fails CI.

Some files are intentionally excluded from formatting via `project.excludeFilters` in
`.scalafmt.conf`: they use experimental capture-checking `^{...}` return-type annotations in a
position scalafmt's parser does not yet support. Files that merely *use* `^{...}` but still parse
(e.g. `src/shared/derivation/WorkflowEvents.scala`, `test/shared/unit/CCTest.scala`) stay
formatted normally.

⚠️ `scala-cli fmt --check` reports a scalafmt parse error but still **exits 0**, silently masking
unformatted files. So when you add a new file with CC syntax scalafmt can't parse, add it to
`project.excludeFilters` — otherwise it hides real formatting failures. The CI job greps the
output for parse errors and fails loudly to catch this.

---

## Testing

Four legs, exactly as CI runs them (`.github/workflows/ci.yml`, one `test` matrix job with a
`jvm` and a `js` include entry — a future Scala Native port is a new include entry, not a new
job; each leg runs compile + unit tests + integration tests):

- **JVM unit tests** (no Docker): `scala-cli test . --test-only 'dapr4s.test.unit.*'`. Shared
  suites live in `test/shared/unit/` (`JsonCodecTest`, `ModelsTest`, `StateCapabilityTest`,
  `CCTest`, `CapabilityHandlerTest`, derivation tests, ...); JVM-only suites in `test/jvm/unit/`
  (`SubscriberTest`, `BindingDispatchTest`, `JobDispatchTest`, `DaprServerTestBase` — they drive
  the JVM `DaprAppServer` over real HTTP on `com.sun.net.httpserver` — plus the Jvm* derivation
  and models tests). `test/jvm/TestCodecs.scala` (Jackson) and `TestDaprExtensions.scala` are
  jvm-only helpers; `test/js/TestCodecsJs.scala` provides the same codec given names over ujson
  so the shared suites run unchanged on JS.
- **JVM integration tests** (Docker):
  `scala-cli test . --test-only 'dapr4s.test.integration.*'`. Suites in `test/jvm/integration/`
  use `testcontainers-scala-munit` with the `TestContainersForAll` pattern against a real Dapr
  sidecar. `startContainers()` **must return an already-started container** — call `c.start()`
  before returning; the framework does NOT auto-start it. Tests use `withContainers { c => }`.
  The `DaprTestContainer` wrapper bridges the testcontainers-scala `SingleContainer` type to the
  Dapr Java testcontainers type. The `DaprApp` fixtures in `test/shared/apps/` deliberately
  **cross-compile** (`CapabilityHandlerTest` exercises them on Scala.js — do not jvm-tag them);
  only the two `*Main.scala` entry points are jvm-only (`test/jvm/apps/`).
- **Scala.js unit tests** (no Docker, no npm, no facade-touching code):
  `scala-cli test --js . --exclude test/js/integration --test-only 'dapr4s.test.unit.*'`.
  Runs the shared unit suites on the **plain JS backend** under Node. The `--exclude` is
  mandatory and load-bearing: the integration suites contain orphan `js.await`, and the plain-JS
  linker **wedges on orphan-await test sources** (hangs, no error) — they must not even be
  linked on this leg.
- **Scala.js integration tests** (Docker + Node >= 25 first on PATH + the ST facades +
  `npm ci`): `scripts/test-js-integration.sh` runs the 9 munit suites in `test/js/integration/`
  on the **Wasm+JSPI backend**. The Dapr sidecar (Redis-backed canonical components + placement +
  scheduler) is started from INSIDE the test runtime by `@dapr/testcontainer-node` — the twin of
  the JVM `testcontainers-dapr` leg — via `DaprJsItFixtures.scala`; there is no separate env to
  bring up/down (testcontainers and its Ryuk reaper own the containers). Direct-call suites run
  per-suite (`SharedDaprJsItSuite`, rotated forward since `afterAll` can't await on JS); the four
  server-delivery suites (actor/pub-sub/invoke/workflow) share ONE sidecar + ONE in-process union
  server (`ServerDaprJsItSuite` + `jsItUnionApp`), reached via `host.testcontainers.internal` with
  daprd app health checks, because `serve` suspends forever with no clean stop on JS. Harness
  facts: `scripts/wasm-test.sh` tolerates the known scala-cli bug of exiting 1 after a green Wasm
  run (`DirectoryNotEmptyException` on the linked-output dir); an ESM resolution hook
  (`scripts/js-it/node-resolve-hook.mjs`) lets the `/tmp`-linked test module import bare specifiers
  from the repo's node_modules (ESM ignores NODE_PATH/CWD) and appends `.js` for the deep
  CommonJS submodule paths ScalablyTyped emits for testcontainers; `--test-only` is **ineffective
  on the JS test runner** — the unit suites run alongside, harmlessly; `java.util.UUID.randomUUID()`
  does **not link** on Scala.js (SecureRandom is absent from the javalib) — use
  `JsItEnv.uniqueId()`.
- CI gates `publish` on the `format` job and **both** matrix legs passing.
- After every non-trivial change: compile first, then run unit tests, then integration tests if
  relevant. Do not batch large changes and test only at the end.

---

## Allium spec and DESIGN.md

`docs/SPEC.allium` is the formal specification of the library's behaviour written in the Allium DSL.
`docs/DESIGN.md` is the architecture and design document with Mermaid diagrams.

Both must stay in sync with the code. If you change the code, update the spec and design doc.
If you change the spec or design doc, update the code to match. Verify Mermaid diagrams are
valid and renderable. The Allium spec is maintained using the `allium:distill` and `allium:elicit`
skills.

---

## Wiki (LLM wiki — lives in this repo)

**dapr4s contains its own LLM wiki**, checked into this repository — not an external one. It has
two parts at the project root: `wiki/` (curated, cross-linked articles plus `index.md` and a
`log.md` change log) and `raw/` (the immutable ingested source material the articles are compiled
from). Both are organised into topic subdirectories.

Topics currently covered:
- **dapr** / **kubernetes** — DAPR building blocks, Java SDK internals, Testcontainers/E2E, k8s stacks.
- **scala-capture-checking** / **capabilities-research** / **effect-systems** — Safe Scala, capture
  checking, capability-based type systems and the research papers behind them.
- **scala-effect-libraries** — Kyo, Ox, Gears, Effekt, comparison for the wrapper design.
- **scala3-language** / **scala-type-safety** — opaque types, given/using, context functions,
  parse-don't-validate, ADTs, nrinaudo best practices.
- **scala3-metaprogramming** — the Scala 3 macro/metaprogramming reference (inline, compile-time ops,
  quotes & splices, TASTy reflection, runtime staging) plus the cross-version Scala-Hearth library.
- **scala-rpc-derivation** — Scala 3 libraries that derive an implementation *from a trait* (RPC
  clients, routers, tagless algebras, proxies, DI): the landscape, the shared `quotes.reflect`
  class-synthesis mechanism, and per-library write-ups (sloth, automorph, oxygen, spice, kreuzberg,
  smithy4s-deriving, ops-mirror, zio-blocks RPC, cats-tagless, tagless-redux, ZIO IsReloadable,
  distage TraitConstructor).

**Interact with it via the `karpathy-llm-wiki` skill** — it handles the full pipeline (save raw
source, synthesise/merge the article, update `index.md`, log to `log.md`). Use it to *query* the
wiki ("what do I know about X"), to *ingest* new sources, and to *lint* for broken links / stale
or orphaned articles. Start from `wiki/index.md` to orient.

**Use the wiki actively**: before researching a topic, check if it's already covered. After
researching a topic (especially SDK internals, Scala compiler behaviour, or library internals),
add or update the relevant wiki article. The wiki is a compounding asset — keep it current.
Learn from this wiki and save all what you learn continually during each session.

**Continually lint the wiki**: run the `karpathy-llm-wiki` lint after ingesting sources or
editing articles, and periodically even when you haven't, to catch broken links, index drift,
orphaned pages, and stale or contradictory content. Fix the deterministic issues and keep the
index and `log.md` in sync.

When investigating the DAPR Java SDK or any external library's internals, document findings in
the wiki regardless of whether the investigation yields an actionable change.

---

## Research methodology

When a question about library internals, JVM behaviour, or SDK design comes up:
1. Check the wiki first.
2. Read actual source code — not just README or docs. Use GitHub search/browse or clone.
3. Be sceptical of secondary sources; verify claims by reading the code.
4. Challenge assumptions ("Is this actually an improvement?", "How is that different?").
5. Document findings in the wiki with specific class names, method names, and reasoning.
6. If a finding leads to a contribution opportunity (like a missing feature in a dependency),
   design the minimal change, fork the upstream repo, implement it on a branch, and push.
   Do not open a PR without being explicitly asked.

---

## Cellar — JVM API lookup

Use the `cellar` skill to look up exact type signatures, members, and Javadoc for any JVM
library on Maven Central — Java, Scala, or otherwise. Prefer it over cloning repos or manually
probing class files whenever you need to verify an API.

---

## Agent patterns

- **Grumpy reviewer + implementer loop**: for non-trivial implementation tasks, use an implementer
  agent and a grumpy reviewer agent in a loop. The reviewer should be adversarial — looking for
  correctness issues, missed edge cases, style violations, and missing tests.
- **Parallel agents**: for independent research tasks (e.g. reading multiple SDK files, fetching
  multiple wiki sources), spawn agents in parallel.
- **Explore agent**: for broad codebase searches across multiple files or naming conventions.
- Keep the main context window clean — delegate heavy research or multi-file reading to subagents.

---

## GitHub workflow

- Work in independent worktrees, but push directly to `master`/`main`.
- **Commit and push completed work immediately and automatically — do not wait to be asked.**
  Once a change is done and the checks pass (format, compile, tests), commit it and push to
  `master`/`main` without prompting for permission. This applies to *any* edit you make — code,
  docs, config, tests, a one-line fix — with no exceptions. Never end a turn by offering
  "commit and push, or leave it in the working tree?" as a choice — just push. Pushing to
  `master` here is the default, not an action that needs confirmation. Likewise, never make an
  edit and then *stop* with it left uncommitted in the working tree: finishing an edit means
  committing and pushing it in the same turn. If the user later has to ask "did you push this?",
  the rule was broken.
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, etc.
- When forking external repos for contribution proposals: fork via `gh repo fork`, create a
  branch with a descriptive name (`feat/blocking-client-virtual-threads`), implement, compile,
  push. Do not open a PR without being asked.
- Do not force-push, do not skip hooks, do not amend published commits.
- **Releases**: never create or push a bare `vX.Y.Z` tag by hand — always cut the release as a
  proper GitHub Release (`gh release create vX.Y.Z`), which creates the tag for you. The
  tag-pushing path exists only as the CI trigger; the human-facing action is always the GitHub
  Release. Make it pretty: a descriptive title and release notes grouped by Conventional Commit
  type (Features / Fixes / Docs / Build), with the highlights called out at the top. Prefer
  curated notes over a raw auto-generated commit dump.

---

## What we are NOT doing

- No effect library dependency (Kyo, Ox, Cats Effect, ZIO) — the capability-passing approach
  via `?=>` context functions is sufficient and keeps the user API minimal.
- No async/`Future`-based API — the library is synchronous and blocking, designed for virtual
  threads.
- No exposing Reactor/Mono types to users — all Reactor is confined to `src/jvm/internal/`.
- No runtime stubs for platform-absent building blocks — a capability the platform SDK lacks
  (jobs/conversation on JS) is compile-time absent via the platform-trait technique, never an
  `UnsupportedOperationException`.
