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

Refer to `docs/DESIGN.md` for the full architecture, to `docs/SPEC.allium` for the formal specification, and
to `docs/CAPABILITY-HIERARCHY.md` for the class hierarchy of every capability interface.
All three must stay in sync with the code at all times.

---

## Scala stack

- **Scala version**: latest nightly (`3.9.0-RC1-bin-*-NIGHTLY` or later), pinned as `Versions.scala3Nightly`
  in `buildsupport.mill` (`./mill show scala3Nightly` prints it; the wasm modules and the scripts read it
  from there). Always use the newest available nightly to get the latest capture-checking and
  safe-mode fixes. Nightlies resolve from the scala-lang nightly repository (a build-only resolver,
  never written into the POM).
- **Build tool**: **Mill 1.3.0** (`build.mill`, `buildsupport.mill`, `wasm/package.mill`, and the
  meta-build sources `mill-build/src/` = the `mill-dapr4s-wasm` plugin; `./mill` is the bootstrap
  script, so nothing needs to be installed).
  History: scala-cli until 2026-10-12, sbt until 2026-10-13 (the sbt build lived in `build.sbt`,
  `project/` and a nested `wasm/build.sbt`; `git log` has it). Modules:
  - `core.jvm` / `core.js` — the published library (`dapr4s_3` / `dapr4s_sjs1_3`); `core.jvm.test` /
    `core.js.test` are its **unit** tests (every test source outside an `integration/` directory).
  - `integration.jvm` / `integration.js` — the Docker integration suites (`test/{shared,jvm,js}/integration`),
    never published. `integration.js` links with the Wasm + JSPI linker config.
  - `stFacades` — the ScalablyTyped facades (see "Scala.js layer rules"), never published itself;
    `stGen` — the generator of their sources (`build-tools/st-gen`).
  - `k8s.orderService` / `k8s.inventoryService` — runnable fat jars of the two example mains
    (`test/jvm/apps`) for `scripts/k8s-test.sh`.
  - `wasm.*` — the `wasm` platform (WASI components), see "Wasm component layer rules" below.
- **Platforms**: JVM **and Scala.js** (plus the `wasm` platform). Common commands:
  - `./mill core.jvm.compile`, `./mill core.js.compile`
  - `./mill core.jvm.test`, `./mill core.js.test` (unit tests; no Docker)
  - `./mill integration.jvm.testForked`, `./mill integration.js.testForked` (Docker; JS also needs
    Node >= 25 first on the PATH)
  - Several tasks in one call: `./mill a + b`, or brace expansion `./mill core.{jvm,js}.test`.
  - Mill keeps a background daemon, so repeated calls are fast; all output goes to `out/`.
- **Directory layout**: `src/{shared,jvm,js,wasm}` and `test/{shared,jvm,js,wasm}`. Mill does not own
  this layout: each module points its `sources` into it (module directories such as `core/jvm/` do not
  exist). Files under `jvm/`/`js/` still carry a per-file `//> using target.platform "jvm"`/`"scala-js"`
  line from the scala-cli days; it is only a comment (removal is a planned follow-up). Packages are
  unchanged by the layout (`src/jvm/internal/` and `src/js/internal/` are both `dapr4s.internal`).
  Each capability family lives in its own package `dapr4s.<family>` under
  `src/<platform>/<family>/` (`state`, `publish`, `invoke`, `secrets`, `configuration`,
  `bindings`, `lock`, `actor`, `workflow`, `crypto`, `jobs`, `conversation`): the universal
  capability trait (with its `Instance`/`ReadOnly` tiers in its companion — see "Capability
  family shape" below), the family's value types, its inbound route type and its
  derivation macros. Cross-cutting macro infrastructure (`MacroSupport`, `Forwarders`, `@name`,
  annotations) stays in `dapr4s.derivation`; cross-cutting types (`DaprCapability`, `Dapr`,
  `DaprConfig`, `DaprApp`, `JsonCodec`, `HttpMethod`, exceptions, `src/shared/optypes/`) stay in
  `dapr4s`. There is no re-export facade — consumers import the families they use.
  `core.js` links with ES2017 (`esFeatures`), required by `js.async`/`js.await`.
  **JS build prerequisite**: npm (the build runs `npm ci` itself when `node_modules` is missing or
  older than `package-lock.json`). Generating + compiling the facades takes ~10 min cold; later
  builds reuse them (Mill caches `out/stFacades`, the converter `~/.cache/scalablytyped`). The
  published `dapr4s_sjs1_3` jar embeds the facade `.sjsir`, so consumers resolve everything from Maven
  Central.
- **JVM**: Zulu 25. JDK 25 is required for stable virtual thread support (no carrier pinning on
  `synchronized`). The `//| mill-jvm-version: zulu:25.0.3` header of `build.mill` makes Mill download
  and run on Zulu 25 and fork every test JVM with it, whatever JDK is on the PATH;
  `//| mill-jvm-opts` gives the Mill daemon 6 GB heap and 8 MB stacks (the facade compile needs them).
- **Compiler flags** (all active, `Versions.compilerFlags` in `buildsupport.mill`, applied by the
  `Dapr4sFlags` trait; not applied to `stFacades`):
  - `-language:experimental.captureChecking`
  - `-language:experimental.pureFunctions` — `A => B` is a pure function; context functions are
    pure by default; strengthens `Dapr.run`'s body type.
  - `-experimental` — enables clause interleaving (`def f[A](x: A)[B: TC]: B`).
  - `-Ycc-verbose`, `-Yexplicit-nulls`, `-Wconf:any:error` (fatal warnings).
- **Dependencies** (versions in the `Versions` object of `buildsupport.mill`): upickle 3.3.1 (pinned —
  4.x crashes on CC-annotated types, test-only), munit 1.3.0, testcontainers-scala-munit 0.43.6,
  testcontainers-dapr 1.17.2 (all test-only — not part of the published library). Cross deps use
  `mvn"org::name::version"`; `scala-java-time` provides `java.time` on Scala.js. Platform scoping is
  per module: the Dapr Java SDK is a `core.jvm` dependency, testcontainers an `integration.jvm`
  dependency, scala-java-time / scalablytyped-runtime / scalajs-dom `core.js` dependencies. The JS
  layer's npm deps (`@dapr/dapr`, `@types/express`, `@types/node`, `testcontainers`,
  `@dapr/testcontainer-node`, `typescript`) are declared in `package.json` (with
  `package-lock.json` committed); `build.mill` (`npmRoots`) reads the `dependencies` of
  `package.json` as the ScalablyTyped conversion roots, so `package.json` is the single source of truth.
- **POM**: the POMs must stay identical to the scala-cli/sbt era ones (coordinates, license, developer,
  scm, url, organization; dependency lists). `Dapr4sPom` (`buildsupport.mill`) renders them in exactly
  sbt's layout (Mill's own renderer differs); `core.jvm` depends on `scala3-library_3` (as sbt did, not
  Mill's default `scala-library` for Scala >= 3.8); test dependencies never reach a POM; `core.js`
  keeps `stFacades` out of it. Check: `./mill core.{jvm,js}.pom` and diff against the published POMs
  (only the version may differ).
- **Versions**: `publishVersion` (the `Dynver` object in `buildsupport.mill`) keeps sbt-dynver +
  sbt-ci-release's scheme: a clean checkout at a `vX.Y.Z` tag is `X.Y.Z`, anything else
  `X.Y.Z+<distance>-<sha8>[+<yyyyMMdd-HHmm> if dirty]-SNAPSHOT`.

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

### Capability family shape (universal + Instance + ReadOnly)

Every building block follows the same shape (design rationale: `docs/CAPABILITY-SPLITS.md` §11;
overview: `docs/DESIGN.md` "Capability Hierarchy"). Keep new members and new families on it:

- The principal capability `X` is **universal** — not bound to any name; each method takes the
  name (`StateCapability.save(storeName, key, value)`, `InvokeCapability.invoke(appId, ...)`).
  `DaprCapability.x` returns it (`def state: StateCapability^{this}`).
- `X.Instance` (in `X`'s companion) is bound to one name via `X.instance(name)`, carries it as a
  `val` (`storeName`, `appId`, ...) and its methods do not take it.
- Only families with a read-only subset — State, Secrets, Configuration, Jobs, Workflow — also
  have `X.ReadOnly` (super-trait of `X`) and `X.Instance.ReadOnly` (super-trait of `X.Instance`).
  `X.ReadOnly.instance(name)` returns `X.Instance.ReadOnly`; `X` overrides it **covariantly** to
  return `X.Instance`. Never add an `instance` that returns a wider tier than its receiver — narrowing
  by name must never re-widen authority. Do not add `ReadOnly` tiers to Publish, Invoke, Bindings,
  Lock, Crypto, Actor or Conversation (no read-only subset).
- Actor is two-level: `ActorCapability` → `forType(actorType)` → `ActorCapability.Type` →
  `instance(actorId)` → `ActorCapability.Instance`.
- Every interface's companion object forwards **all** of its methods (inherited ones included)
  from a `using` capability, plus `instance(name)` accessors that narrow the capability in scope and
  return it as `X.Instance^{cap}` (`X.instance`, `X.ReadOnly.instance`; actor: `ActorCapability.forType(t)`,
  `ActorCapability.instance(t, id)`, `ActorCapability.Type.instance(id)`).
- Derivation macros require the **narrowest** tier the generated member uses: read-only getters
  need only `X.Instance.ReadOnly` (State/Secrets/Configuration) or `JobsCapability.ReadOnly` (jobs);
  Invoke/Workflow/Jobs derivation call the universal capability directly.
- There are no `Access*Capability` traits and no `WorkflowInstanceCapability`/`ActorTypeCapability`/
  `JobCapability` any more — do not reintroduce them.
- Handle-style sugar exists only for ids you hold and act on: `WorkflowInstanceId` (companion extensions:
  `id.getStatus`, `id.waitForCompletion(t)`, `id.suspend()`, …) and `JobName` (`src/jvm/jobs/JobNameOps.scala`:
  `name.get`, `name.schedule(...)`, `name.delete()`, …). Each extension must require the **narrowest** tier that
  carries the operation (reads: `X.ReadOnly`; mutations: the full `X`) — never the full capability for a read.

### SDK interop boundary (Java on JVM, @dapr/dapr on JS)
Everything in the two `internal/` trees is marked `@scala.caps.assumeSafe`. There are two
platform walls behind the same boundary:

- `src/jvm/internal/` (all jvm-tagged) is the **JVM wall**: the only place Java SDK types may
  appear. Nothing from the Java SDK (`Mono`, `DaprClient`, `GrpcChannel`, proto classes, etc.)
  may appear in `src/` files outside it or in any test file.
- `src/js/internal/` (all js-tagged, same package `dapr4s.internal`) is the **JS wall**: the only
  place `@dapr/dapr` (and express/Node) types may appear. Those types are the
  **ScalablyTyped-generated facades** (`dapr4styped.daprDapr`, `dapr4styped.expressServeStaticCore`,
  `dapr4styped.node`, ... — generated into the dapr4s-specific `dapr4styped.*` root package by the
  `stFacades` module of build.mill), plus the single surviving hand-written shim in
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
- Everything reachable only from that surface moves wholly under `src/jvm/` (same family
  packages `dapr4s.jobs`/`dapr4s.conversation` as their shared parts):
  `JobsCapability` (+ its `ReadOnly`/`Instance` tiers) and `ConversationCapability` (+ `Instance`)
  (`src/jvm/jobs/JobsCapability.scala`, `src/jvm/conversation/ConversationCapability.scala`),
  their models (`src/jvm/jobs/JobsModels.scala`, `src/jvm/conversation/ConversationModels.scala`),
  their impls, the `Jobs.derive` engine (`src/jvm/jobs/Jobs.scala`), and the jobs runtime forwarders
  (`Forwarders extends ForwardersPlatform`; `Forwarders.jobRoute` stays shared because the
  inbound job-trigger side is cross-platform; likewise the cross-platform `JobName`/`JobRoute`/
  `JobRoutes` stay in `src/shared/jobs/`).
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
- **Facades are ScalablyTyped-generated**, not hand-written. The `stFacades` module converts the npm
  packages of `package.json` `dependencies`, as installed in the repo's `node_modules` from
  `package-lock.json` (`npmInstall` runs `npm ci` when needed), into Scala sources
  (`out/stFacades/stSources.dest`) and compiles them (~10 min cold; the converter's parse cache is
  `~/.cache/scalablytyped`). The generator is `build-tools/st-gen` (`stGen` module): it calls
  sbt-converter's own `ImportTypingsGenSources` (converter `1.0.0-beta45`; the sbt plugin jar is used
  as a plain library, without sbt) with the options the sbt build used: output package `dapr4styped`,
  stdlib `es2022`, Scala 3.3.6 (the converter needs a released Scala to target; the nightly reads its
  TASTy), plugin defaults otherwise — its output is byte-identical to the sbt build's. WHY not
  `mill-scalablytyped`: 0.4.1 hard-codes stdlib `es6`; RETIRES with
  [lolgab/mill-scalablytyped#51](https://github.com/lolgab/mill-scalablytyped/pull/51) (stdlib option) +
  [#52](https://github.com/lolgab/mill-scalablytyped/pull/52) once released. Facade imports use the
  **`dapr4styped.*` root package**, never ST's default `typings.*` — the facades ship inside the
  published jar and must not collide with a consumer's own ST generation.
  `stFacades` is compiled without our flags (no CC, no explicit nulls, no fatal warnings) — but
  OUR code must still compile with zero warnings, and explicit-nulls means ST results must NOT be
  `.nn`-ed (it is an error: unnecessary `.nn`).
- **The published `dapr4s_sjs1_3` jar is self-contained**: `core.js` depends on `stFacades` (a
  `moduleDep`), but filters it out of `publishXmlDeps`/`publishMvnDeps` (so it is not in the POM) and
  adds the facade `.sjsir` (`stFacades.embeddedFacades`) to its `jar`. Only `.sjsir`
  is embedded, not `.class`/`.tasty`: consumers only link against the facades, and embedded
  `.tasty` made scaladoc produce a 1.1 GB `-javadoc.jar` (0.20.0 publish failure). Only the
  packages of the MAIN roots' closure (`mainFacadePackages`) are embedded; the testcontainers
  closure (`testOnlyFacadePackages`) stays out. Every generated `dapr4styped.<pkg>` must be in
  exactly one of the two lists, or the build fails — classify new packages when npm deps change.
  Consumers resolve dapr4s from Maven Central alone (the POM carries scalablytyped-runtime +
  scalajs-dom as regular deps for the embedded facades).
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

### Wasm component layer rules

The `wasm` platform builds a dapr4s app as a WASI **component** for
[dapr-wasm-components](https://github.com/sideeffffect/dapr-wasm-components) 0.7.5, with the
scala-wasm fork of Scala.js (`io.github.scala-wasm`, `ModuleKind.WasmComponent`).
Status, blockers and numbers: `docs/WASM-COMPONENTS-PROPOSAL.md` §6 and
`wiki/scala-js/scala-wasm-component-spike.md`. The seven artifacts are **published to Maven Central**
by the same `publish` job as core (snapshots on master pushes, releases on tags); `publishLocal` works.
Upstream gaps are worked around here AND fixed upstream (the earlier "no upstream PRs" rule was lifted on
2026-10-13). Open upstream PRs, each retiring one workaround: scala-wasm/scala-wasm
[#268](https://github.com/scala-wasm/scala-wasm/pull/268) (params via memory → patched linker),
[#269](https://github.com/scala-wasm/scala-wasm/pull/269) (WIT aliases → re-embed),
[#270](https://github.com/scala-wasm/scala-wasm/pull/270) (pluggable System → println/clock rules),
[#271](https://github.com/scala-wasm/scala-wasm/pull/271) (core-lib exclusions),
scala/scala3 [#27300](https://github.com/scala/scala3/pull/27300) (unpickler crash → empty javadoc),
cquiroz/scala-java-locales [#640](https://github.com/cquiroz/scala-java-locales/pull/640) (java.time formatting).
**scala/scala3 and VirtusLab/scala-cli forbid LLM-written PR/issue text** (their `LLM_POLICY.md`): there,
agents push branches / open drafts with an EMPTY description; the user writes the text.

- **In the main build, isolated by the fork swap** (`wasm/package.mill`). With sbt, the fork's
  `sbt-scalajs` could not share a meta-build with mainline's, hence a nested `wasm/build.sbt` until
  2026-10-13. Mill has no such clash. **The build logic is the Mill plugin `mill-dapr4s-wasm`**, whose
  sources (`mill-build/src/dapr4s/millwasm/Dapr4sWasmModules.scala`) are compiled into dapr4s's own
  meta-build, so the `wasm.*` modules use the very traits a Mill consumer gets:
  `ScalaWasmModule` swaps `org.scala-js` for `io.github.scala-wasm` in the tasks that name it
  (`mandatoryMvnDeps`, `scalacPluginMvnDeps`, `scalaJSLinkerClasspath`); `Dapr4sWasmModule` adds the
  exclusions; `Dapr4sWasmTests` the fork's test bridge; `WitModule` / `WitBindgenModule` run `wkg wit fetch`
  / wit-bindgen-scala; `Dapr4sWasmComponentModule` links a component. Only `wasm.*` modules see the fork,
  so `core.js` is untouched. Mill's Scala.js worker links the plain-JS wall unit tests with the fork's
  released 2.13 linker; Mill knows no `ModuleKind.WasmComponent`, so COMPONENTS are linked by the driver
  `dapr4s.wasmlink.WasmLink` (`wasm/linker/src`, shipped IN `dapr4s-wasm-linker`), run by the plugin in a
  separate JVM on the linker's classpath. dapr4s's components (`WasmComponent` in `wasm/package.mill`)
  only point the artifact-backed tasks at in-build modules: `dapr4sWasmLinkerClasspath` → `wasm.linker`,
  `dapr4sWasmScalalibOverlay` → `wasm.scalalibOverlay`, `dapr4sWasmAddGlueExports = false` (a module
  dependency on `wasm.glueExports` instead); every module shares `wasm.wit` (the `wasm` object is itself a
  `WitModule`). Nothing in `wasm/` runs unless a `wasm.*` task is asked for: `./mill core.jvm.test` needs
  no wasm tool. The nightly, `scalaJavaTime` and `munit` come from `Versions`; `NightlyWasm` uses the same
  compiler flags. **Upstream (retire the plugin's Mill workarounds)**:
  [com-lihaoyi/mill#7783](https://github.com/com-lihaoyi/mill/pull/7783) (Scala.js organization +
  `ModuleKind.WasmComponent` → `ScalaWasmModule`'s overrides and the link driver),
  [#7784](https://github.com/com-lihaoyi/mill/pull/7784) (wit-bindgen-scala task → `WitBindgenModule`),
  [#7785](https://github.com/com-lihaoyi/mill/pull/7785) (run/test components with wasmtime → the shell
  e2e's `wasmtime serve` wiring could become Mill tasks).
- **The seven artifacts** (all `com.github.sideeffffect`, at core's `publishVersion`, POM metadata as core's,
  normal suffixes):
  | artifact | module | contains |
  |---|---|---|
  | `dapr4s-wasm_sjs1_3` | `wasm.lib[release].wall` | the Scala 3 adapters (`src/wasm/internal`: `WasmStateCapability`, `InvokeDispatcher`, `WasmRuntime`, the real `AppRoot`) + `dapr4s.wasm.DaprComponent` (`src/wasm/api`, module `api`) + the inert callbacks (`src/wasm/inert`, module `wasm.inert`), the last two merged into its jar. Depends on dapr4s core (`dapr4s_sjs1_3`) + `dapr4s-wasm-glue`; every POM dependency carries the exclusions below |
  | `dapr4s-wasm-glue_sjs1_2.13` | `wasm.glue` | the wit-bindgen-scala bindings of world `app` + the hand-written 2.13 glue |
  | `dapr4s-wasm-glue-exports_sjs1_2.13` | `wasm.glueExports` | the hand-written `@WitExport` trampolines (a COMPONENT's dependency only; the plugin adds it) |
  | `dapr4s-wasm-scalalib-overlay` | `wasm.scalalibOverlay` | the fork-patched stdlib `.sjsir` (unmodified, from `io.github.scala-wasm:scalajs-scalalib_2.13:3.8.3-RC1-wasm.4`) + LICENSE/NOTICE attribution |
  | `dapr4s-wasm-linker_2.12` | `wasm.linker` | scala-wasm's 1.22.1-wasm.6 linker with our patch (`scripts/wasm/patches/scala-wasm-params-via-memory.patch`): the patched sources compiled by Mill, the released jar's resources, LICENSE/NOTICE, patched sources in the sources jar; depends on the released ir/linker-interface. Also carries the component link driver `dapr4s.wasmlink.WasmLink` (`wasm/linker/src`) |
  | `mill-dapr4s-wasm` (`_mill1_3`) | `wasm.millPlugin` | the build logic for Mill consumers (below): the sources of `mill-build/src`, compiled with the Scala and against the `mill-libs` of the Mill running the build (1.3.0), + a `dapr4s/millwasm/version` resource (the version of the wasm artifacts it resolves) |
  | `sbt-dapr4s-wasm` (`_2.12_1.0`) | `wasm.sbtPlugin` | the build logic for sbt consumers (below), the twin of `mill-dapr4s-wasm`. Mill builds it as a plain sbt-1 plugin jar: sbt `provided`, a generated `sbt/sbt.autoplugins`, `Implementation-Version` in the manifest, sbt's POM properties |
- **Core: published or source** (`wasm.lib` is a cross module). `wasm.lib[release]` (the default for
  publishing and `scripts/wasm/e2e.sh`): the wall depends on the PUBLISHED `dapr4s_sjs1_3`
  (`CoreRelease` in `wasm/package.mill`, or `DAPR4S_WASM_CORE_VERSION`), whose Scala.js IR the scala-wasm
  linker links into the component (the JS wall is unreachable and dropped; mainline org.scala-js
  library/javalib are excluded). Valid because the wasm platform traits (`src/wasm/core`) equal the JS
  ones (all empty). `wasm.lib[source]` compiles `src/shared` + `src/wasm/core` as `wasm.coreSource`
  instead, so shared-code changes are tested (nothing publishes from it). A release publishes the wasm
  artifacts against the core of the SAME version (the publish job sets `DAPR4S_WASM_CORE_VERSION` and
  `publishLocal`s `core.js` first). Bump `CoreRelease` after each core release.
- **Exclusions** (`Exclusions` in `wasm/package.mill`, applied by `NightlyWasm` to every dependency):
  a nightly-compiled module must not see the 2.13 stdlib the fork adds to the 2.13 glue, nor mainline
  `org.scala-js` library/javalib (different organization from the fork's: no eviction, first IR file per
  class wins). They are written into the `dapr4s-wasm` POM, so consumers inherit them.
- **The Mill consumer** needs only:
  `//| mvnDeps: ["com.github.sideeffffect::mill-dapr4s-wasm::<v>"]` in the `build.mill` header,
  `import dapr4s.millwasm.*`, a module `extends Dapr4sWasmComponentModule` with
  `def mvnDeps = Seq(mvn"com.github.sideeffffect::dapr4s-wasm::$dapr4sWasmVersion")` (+ its Scala 3
  nightly `scalaVersion` and a JVM worker that resolves the nightly), its own `wit/world.wit`
  (`include dapr-wasm-components:interfaces/app@0.7.5;`) + `wkg-config.toml` + `wkg.lock` in the module
  directory (`witFiles` overrides the location), and the fixed-name
  `object dapr4s.wasm.ComponentRoot { def component: DaprComponent = ... }`. `./mill show app.dapr4sComponent`
  (`dapr4sComponentFull`: optimized) prints the component's path. `scripts/wasm/consumer-check-mill.sh`
  publishes the seven artifacts into a local Maven repo (`publishM2Local`), builds `test/wasm/counter`
  exactly so (a fresh Mill build under `out/consumer-check-mill`, header `//| repositories:` pointing at the
  local repo) and runs its e2e; dapr4s-examples (examples 18/19) is the worked example.
- **The sbt consumer** needs only:
  `addSbtPlugin("com.github.sideeffffect" % "sbt-dapr4s-wasm" % v)`,
  `.enablePlugins(Dapr4sWasmComponentPlugin)`,
  `libraryDependencies += "com.github.sideeffffect" %%% "dapr4s-wasm" % dapr4sWasmVersion`, its own
  `wit/world.wit` + `wkg-config.toml` + `wkg.lock`, and the fixed-name `ComponentRoot`.
  `scripts/wasm/consumer-check.sh` does the same for sbt (a fresh sbt build). It is the only sbt left in
  the repo's checks: the sbt plugin is a product for sbt users.
- **The two plugins** (each workaround documented at its definition with the upstream PR that RETIRES it;
  keep them in sync):
  - `mill-dapr4s-wasm` (`mill-build/src/dapr4s/millwasm/Dapr4sWasmModules.scala`): `ScalaWasmModule` (fork
    swap), `Dapr4sWasmModule` (+ exclusions; `dapr4sWasmVersion` = the plugin's own version, from the jar's
    resource), `Dapr4sWasmTests`, `WitModule` (`witFiles` → `wit`, `wkg wit fetch`), `WitBindgenModule`
    (`witBindgenBasePackage`, `witBindings`; `wasm.glue` uses it), `Dapr4sWasmComponentModule`
    (`dapr4sWasmLinkerClasspath`, `dapr4sWasmScalalibOverlay` — both resolved as TOOLS from the module's
    repositories, not with `defaultResolver()`, which would force the app's Scala 3 `scala-library` onto
    the 2.12 linker — `dapr4sWasmAddGlueExports`, `dapr4sComponent` / `dapr4sComponentFull`: link driver
    with the overlay first, then the utf16 re-embed of the real WIT world + `component new`).
  - `sbt-dapr4s-wasm` (`wasm/sbt-plugin/.../Dapr4sWasmPlugin.scala`): `Dapr4sWasmPlugin` (every
    nightly-compiled project) excludes the 2.13 stdlib and mainline `org.scala-js` library/javalib and sets
    the linker to `dapr4s-wasm-linker` (`dapr4sWasmLinkerClasspath`); `Dapr4sWasmComponentPlugin` (every
    component) sets `ModuleKind.WasmComponent`, prepends `dapr4s-wasm-scalalib-overlay` to the linker
    classpath (`dapr4sWasmScalalibOverlay`, resolved through the linker's GLOBAL `dependencyResolution`
    because coursier's per-project one applies `excludeDependencies`), adds `dapr4s-wasm-glue-exports`
    (`dapr4sWasmAddGlueExports`), runs `wkg wit fetch` and provides `dapr4sComponent` /
    `dapr4sComponentFull` (re-embed the real WIT world with `--encoding utf16` + `component new`).
- **How each workaround retires** (both plugins): patched linker → scala-wasm#268 (WIT params through
  linear memory on both sides): drop `dapr4s-wasm-linker` and the plugins' linker override; WIT re-embed →
  scala-wasm#269 (the linker keeps WIT aliases): use its component as is; exclusions → scala-wasm#271 (the
  fork's core libraries evict mainline's); overlay → when the nightly's library-js is Wasm-clean, drop
  `dapr4s-wasm-scalalib-overlay`; the 2.13 glue → when Scala 3 gets WIT codegen; Mill only: the fork swap
  (`ScalaWasmModule`) + the link driver → mill#7783, `WitBindgenModule` → mill#7784, running/testing
  components → mill#7785.
- **The patched linker** (`scripts/wasm/prepare-patched-linker.sh`, run by `wasm.linker.patchedSources`:
  `git archive` of the release commit's linker sources from a cached clone in
  `~/.cache/dapr4s/scala-wasm-src` + the patch; Mill compiles them, no scala-wasm sbt build): the
  released linker leaves the EXPORT side of
  params-via-memory (> 16 flat params) a `???` (`on-topic-event`, `on-timer` of the mandatory callbacks)
  and silently emits NOTHING on the IMPORT side (`jobs.schedule`, `conversation.converse`,
  `actors.register-reminder`/`-timer`; `component new` then fails "expected i32 but nothing on stack").
  The patch implements both (canonical ABI `lift_flat_values` / `lower_flat_values`).
  `test/wasm/imports-probe` calls the four imports; the e2e checks it imports exactly them and runs
  them against daprd (daprd's answers name the component/actor the probe sent).
- **WIT only through OCI**: `world app { include dapr-wasm-components:interfaces/app@0.7.5; }`
  + the upstream `wkg-config.toml`; `wasm.wit` runs `wkg wit fetch --config wkg-config.toml` on a copy in
  `out/` before wit-bindgen-scala. `wkg.lock` is committed; `wit/deps/` never lands in the source tree.
  Never copy WIT from git.
- **Newest everything**: the glue tracks the WIT version (`wasm/wit/world.wit`, the `WitScope`
  versions in the trampolines). Always the newest dapr-wasm-components WIT release (now 0.7.5), the
  newest scala-wasm release on Central (now 1.22.1-wasm.6, `ScalaWasmVersion`) and the newest
  wit-bindgen-scala (0.1.0).
- **The two-compiler constraint**: mainline Scala 3 has no WIT codegen (imports are lowered at
  the call site by the compiler backend, `@WitExport` is compiler-emitted). Every import call and
  every export must be compiled by Scala 2.13 + scala-wasm's compiler plugin. The 2.13 code is
  kept **small and hand-written** (no generator), covering only what is used:
  1. `wasm.glue` holds the wit-bindgen-scala BINDINGS (generated by `wit-bindgen-scala` with the
     arguments the fork's sbt plugin used) under `dapr4swit`. Scala 3 uses their record/variant/enum types
     directly (`state_read.GetError.KeyNotFound`, exhaustivity checking works), but cannot call
     their `@WitImport` defs. There is no facade.
  2. `wasm.glue` also compiles `src/wasm/glue/WitGlue.scala` (package `dapr4s.internal.witglue`): one
     plain trait per imported interface used (`StateReadImports`, `StateImports`; `JobsImports`,
     `ConversationImports`, `ActorsImports` for the probe) whose companion object is the live
     implementation (the only import call sites), and the 7 `*CallbackExports` traits.
  3. `wasm.glueRootStub` (`src/wasm/glue-root-stub`): the `AppRoot` link-time stub; `wasm.glueExports`
     (`src/wasm/glue-exports`): the 15 `@WitExport` trampolines of the 7 callback interfaces.
  A new building block on the wall = a new import trait + live object in `WitGlue.scala` (copy the
  signatures from the generated bindings under `out/wasm/glue/witBindings.dest`). WIT resources
  (`crypto`, configuration `subscription`) are not wired yet.
- **The wall depends on the import TRAITS, never on the import functions**, and receives their
  live objects only in `WasmRuntime`. That is what keeps it unit-testable with fakes.
- **Only components depend on the trampolines**: their `@WitExport`s are linker entry points, and a
  plain-JS test link would reject them ("Invalid top level export").
- **No `main` in a reactor**: the app supplies `object dapr4s.wasm.ComponentRoot { def component
  = MyComponent }` (fixed FQN, resolved at link time against a compile-only stub; a missing one is
  a link error). The 2.13 trampolines reach the Scala 3 wall the same way, via
  `dapr4s.internal.witglue.AppRoot`. The stubs are never published and are filtered out of the POMs
  (`compileModuleDeps` + `embeddedModules`; on a linker classpath they would shadow the real objects:
  first IR file per class wins).
- **Central publishing switch**: the `publish` job of `.github/workflows/ci.yml` sets
  `PUBLISH_WASM_TO_CENTRAL: 'true'` (ON since 2026-10-13, approved by the user); with it the job adds
  the seven wasm artifacts to the same `SonatypeCentralPublishModule/publishAll` call as core. Nothing local
  reaches Central: publishing happens only through that command with the Central credentials.
- **Shared code on the `wasm` path** must avoid `java.time` formatting/parsing, wall-clock
  (`Instant.now`), `println` and `UUID.randomUUID`: they reach JS and fail to link.
- Commands (tools: `wkg`, `wasm-tools`, `wit-bindgen-scala` for the build; plus Docker, the dapr
  CLI + daprd from `dapr init --slim`, `wasmtime` and `wac` for the e2e):
  - `./mill 'wasm.lib[source].wall.test'` / `'wasm.lib[release].wall.test'` (wall unit tests, plain
    Scala.js on Node)
  - `./mill show 'wasm.lib[release].counter.dapr4sComponent'` (prints the component's path; also
    `wasm.counterRaw`, `wasm.importsProbe`; `dapr4sComponentFull` for the optimized link)
  - `./mill 'wasm.lib[release].wall.publishLocal' + wasm.glue.publishLocal + ... + wasm.millPlugin.publishLocal`
    (the seven artifacts, for dapr4s-examples; `publishM2Local` for a Maven layout)
  - `scripts/wasm/e2e.sh` (counter-raw, counter, imports-probe: imports check, compose, daprd + Redis, for the
    probe also a `daprio/dapr` scheduler container and daprd's echo conversation component; the probe asserts
    `converse` → `ok: <echo>`, `schedule` → `ok: scheduled …`, register-reminder/-timer on an unhosted actor type →
    `PermissionDenied`; `WASM_CORE=source` links `counter` from `wasm.lib[source]`)
  - `scripts/wasm/consumer-check-mill.sh` (a plugin-only Mill consumer build of counter + e2e)
  - `scripts/wasm/consumer-check.sh` (a plugin-only sbt consumer build of counter + e2e; needs sbt)

---

## Formatting

The project uses scalafmt. Config is in `.scalafmt.conf` (version 3.10.4, dialect `scala3`,
`maxColumn = 120`, trailing commas everywhere). It covers every git-tracked `*.scala`, `*.sbt` and
`*.mill` file (`project.git = true`): the library, the tests, the wasm sources, `build-tools/` and the Mill
build files. Generated sources (facades, WIT bindings, under `out/`) are never formatted.

Run the formatter: `./mill mill.scalalib.scalafmt.ScalafmtModule/scalafmt`
Check without writing: `./mill mill.scalalib.scalafmt.ScalafmtModule/scalafmt --check`
(Mill runs the scalafmt CLI, which downloads the version `.scalafmt.conf` names.)

**Always format before committing.** The CI `format` job runs the check and blocks the build (and
`publish`) on any unformatted file, so a missed format fails CI.

Some files are intentionally excluded from formatting via `project.excludeFilters` in
`.scalafmt.conf`: they use experimental capture-checking `^{...}` return-type annotations in a
position scalafmt's parser does not yet support. Files that merely *use* `^{...}` but still parse
(e.g. `src/shared/workflow/WorkflowEvents.scala`, `test/shared/unit/CCTest.scala`) stay
formatted normally.

When you add a new file with CC syntax scalafmt can't parse, add it to `project.excludeFilters`.
The scalafmt CLI respects the filters and FAILS the check on a parse error (unlike `scala-cli fmt
--check`, which exited 0 and silently hid the remaining files). The CI job also greps the output
for parse errors and prints a pointer to `project.excludeFilters`.

---

## Testing

Four legs, exactly as CI runs them (`.github/workflows/ci.yml`, one `test` matrix job with a
`jvm` and a `js` include entry — a future Scala Native port is a new include entry, not a new
job; each leg runs compile + unit tests + integration tests). A third include entry, `wasm`, runs
the `wasm.*` modules (the wall's unit tests in both core modes, `scripts/wasm/e2e.sh` in both core
modes, `scripts/wasm/consumer-check-mill.sh`, `scripts/wasm/consumer-check.sh`; see "Wasm component layer
rules"). It installs its pinned
tools, caches the scala-wasm clone the patched linker is built from, and gates `publish` like the other
legs (the wasm artifacts are published by the same job).

- **JVM unit tests** (no Docker): `./mill core.jvm.test`. Shared
  suites live in `test/shared/unit/` (`JsonCodecTest`, `ModelsTest`, `StateCapabilityTest`,
  `CCTest`, `CapabilityHandlerTest`, derivation tests, ...); JVM-only suites in `test/jvm/unit/`
  (`SubscriberTest`, `BindingDispatchTest`, `JobDispatchTest`, `DaprServerTestBase` — they drive
  the JVM `DaprAppServer` over real HTTP on `com.sun.net.httpserver` — plus the Jvm* derivation
  and models tests). `test/jvm/TestCodecs.scala` (Jackson) and `TestDaprExtensions.scala` are
  jvm-only helpers; `test/js/TestCodecsJs.scala` provides the same codec given names over ujson
  so the shared suites run unchanged on JS. Tests run in one forked JVM, suites one after another
  (`testParallelism = false`: the server suites wait for their port with a short timeout).
- **JVM integration tests** (Docker): `./mill integration.jvm.testForked`. Suites in `test/jvm/integration/`
  and `test/shared/integration/` use `testcontainers-scala-munit` with the `TestContainersForAll`
  pattern against a real Dapr sidecar. `startContainers()` **must return an already-started
  container** — call `c.start()` before returning; the framework does NOT auto-start it. Tests use
  `withContainers { c => }`. The `DaprTestContainer` wrapper bridges the testcontainers-scala
  `SingleContainer` type to the Dapr Java testcontainers type. The `DaprApp` fixtures in
  `test/shared/apps/` deliberately **cross-compile** (`CapabilityHandlerTest` exercises them on
  Scala.js — do not move them to a jvm directory); only the two `*Main.scala` entry points are
  jvm-only (`test/jvm/apps/`; `./mill k8s.orderService.assembly` / `k8s.inventoryService.assembly`
  build their runnable fat jars, see `scripts/k8s-test.sh`). The integration modules depend on
  `core.<p>.test`, so they see the apps and test helpers. All suites run sequentially in ONE forked JVM
  (`testParallelism = false`): the server-delivery suites share one sidecar + server.
- **Scala.js unit tests** (no Docker, no facade-touching code): `./mill core.js.test`. Runs the shared
  unit suites on the **plain JS backend** under Node. The integration suites contain orphan
  `js.await`, and the plain-JS linker **wedges on orphan-await test sources** (hangs, no error), so
  they must never be linked here — that is why they live in a separate module, not behind a
  test filter. Every Scala.js test module mixes in `Scala3JsTests` (`build.mill`): Mill prepends the test
  bridge and its TRANSITIVE `scalajs-scalalib_2.13:2.13.x` to the link classpath, whose 2.13 stdlib IR
  would shadow the Scala 3.8+ one ("Referring to non-existent method scala.Some.orNull()").
- **Scala.js integration tests** (Docker + Node >= 25 first on PATH): `./mill integration.js.testForked`.
  `integration.js` links with `ModuleKind.ESModule`, `ESVersion.ES2022`, `scalaJSUseWebAssembly` and
  `scalaJSUseWebAssemblyJSPI` (the JSPI switch Scala.js 1.22 requires; Mill >= 1.3.0 exposes it). Node 25+ has Wasm 3.0 and JSPI on by default. The suites themselves
  are cross-platform and live in `test/shared/integration`; `test/js/integration/` holds only the
  JS bring-up (the per-platform `SharedDaprItSuite` / `ServerDaprItSuite`, plus
  `JsItComponents`/`JsItFacades`/`JsItPolling`/`JsItEnv`). The Dapr sidecar (Redis-backed canonical
  components + placement + scheduler) is started from INSIDE the test runtime by
  `@dapr/testcontainer-node` — the twin of the JVM `testcontainers-dapr` leg; there is no separate
  env to bring up/down (testcontainers and its Ryuk reaper own the containers). Direct-call suites
  run per-suite (`SharedDaprItSuite`, rotated forward since `afterAll` can't await on JS); the four
  server-delivery suites (actor/pub-sub/invoke/workflow) share ONE sidecar + ONE in-process union
  server (`ServerDaprItSuite` + `itUnionApp`), reached via `host.testcontainers.internal` with
  daprd app health checks, because `serve` suspends forever with no clean stop on JS. Harness
  facts: all suites must run in ONE Node runtime (with sbt, `Test / parallelExecution := false` was
  load-bearing: one Node worker per suite binds the same fixed app ports — `EADDRINUSE`, then 404s /
  actor-placement timeouts; Mill's Scala.js test runner uses one runtime). The `jsEnvConfig` passes
  `--import scripts/js-it/node-resolve-hook.mjs` (appends `.js` to the deep CommonJS submodule
  paths ScalablyTyped emits for testcontainers, which Node ESM rejects) and sets
  `DAPR4S_REPO_ROOT` (where the suites find `scripts/it/`). `java.util.UUID.randomUUID()` does
  **not link** on Scala.js (SecureRandom is absent from the javalib) — use `JsItEnv.uniqueId()`.
- CI gates `publish` on the `format` job and all three `test` matrix legs (`jvm`, `js`, `wasm`) passing. `publish` runs
  `./mill mill.javalib.SonatypeCentralPublishModule/publishAll` (Mill's Central Portal publisher: a
  release tag publishes the clean version, a master push a `-SNAPSHOT` to the Central snapshots
  repository) for `core.jvm` + `core.js` (+ the seven wasm artifacts, see "Central publishing switch");
  the scala-cli era secrets (`PUBLISH_USER`, `PUBLISH_PASSWORD`, `PUBLISH_SECRET_KEY`,
  `PUBLISH_SECRET_KEY_PASSWORD`) are mapped to Mill's `MILL_SONATYPE_*` / `MILL_PGP_*` variables in the
  workflow. Check a publish locally with `./mill core.{jvm,js}.publishLocal` (or `.pom`) and inspect
  the POMs; `MILL_TESTS_PUBLISH_DRY_RUN=1` makes `publishAll` a dry run.
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

`docs/CAPABILITY-HIERARCHY.md` holds one Mermaid `classDiagram` per capability family (plus the root
`DaprCapability` and the server-side actor/workflow contexts), showing every interface — universal
`XCapability`, `X.ReadOnly`, `X.Instance`, `X.Instance.ReadOnly`, actor `Type`/`Instance` — with its
members, inheritance and `instance`/`forType` narrowing. It shows the JVM build only (Scala.js is left out for
simplicity). **Keep it in sync with the code**: any change that adds,
removes, renames or re-parents a capability trait, or changes a capability member's signature, must
update the matching diagram in the same commit, and the diagrams must still render (`mmdc`).

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
