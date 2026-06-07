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
- **Build tool**: Scala CLI (`project.scala` using directives). Run tests with
  `scala-cli test . --test-only "*unit*"` for unit tests.
- **JVM**: Zulu 25 (`//> using jvm "zulu:25.0.3"` or later). JDK 25 is required for stable
  virtual thread support (no carrier pinning on `synchronized`).
- **Compiler flags** (all active):
  - `-language:experimental.captureChecking`
  - `-language:experimental.pureFunctions` — `A => B` is a pure function; context functions are
    pure by default; strengthens `Dapr.run`'s body type.
  - `-experimental` — enables clause interleaving (`def f[A](x: A)[B: TC]: B`).
  - `-Ycc-verbose`, `-Yexplicit-nulls`, `-Wconf:any:error` (fatal warnings).
- **Dependencies**: upickle 3.3.1 (pinned — 4.x crashes on CC-annotated types, test-only),
  munit 1.3.0, testcontainers-scala-munit 0.43.6, testcontainers-dapr 1.17.2. (upickle, munit, and
  both testcontainers deps are `test.dep` only — they are not part of the published library.)

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
handlers.onInvoke[Req](InvocationMethodName("my-method"))[Resp] { req =>
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
  - **Method names**: `InvocationMethodName` for service invocation (`ServiceInvocationCapability.invoke`, `InvocationRoute`, `InvocationRequest`) vs. `ActorMethodName` for actor methods (`ActorCapability.invoke`/`invokeVoid`, `ActorMethodRoute`) — an HTTP route on a remote app vs. a method on a stateful actor. (Actor timer/reminder callbacks use `TimerName`/`ReminderName`, not a method-name type.)
  - **Store names**: `StateStoreName` (`DaprCapability.state`) vs. `LockStoreName` (`DaprCapability.lock`) — distinct Dapr components with distinct YAML. (Mirrors the existing `ConfigStoreName`/`SecretStoreName` split.)
  - **State keys**: `StateStoreKey` (app-level `StateCapability`) vs. `ActorStateKey` (per-instance `ActorContext`/`ActorState`).

### Java interop boundary
Everything in `src/internal/` is marked `@scala.caps.assumeSafe`. This is the only place Java
SDK types may appear. Nothing from the Java SDK (`Mono`, `DaprClient`, `GrpcChannel`, proto
classes, etc.) may appear in `src/` files outside `internal/` or in any test file. The
`@assumeSafe` boundary is the wall.

---

## Formatting

The project uses scalafmt. Config is in `.scalafmt.conf` (version 3.10.4, dialect `scala3`,
`maxColumn = 120`, trailing commas everywhere).

Run the formatter: `scala-cli fmt .`
Check without writing: `scala-cli fmt --check .`

**Always format before committing.** If `--check` fails, CI is broken.

One file is intentionally excluded from formatting: `src/DaprCapability.scala` uses experimental
capture-checking `^{this}` return-type annotations that scalafmt's parser does not yet support.
Format it manually (or leave it) until scalafmt gains nightly CC syntax support.

---

## Testing

- **Unit tests** (no Docker required): `scala-cli test . --test-only 'dapr4s.test.unit.*'`. Tests
  across `JsonCodecTest`, `ModelsTest`, `StateCapabilityTest`, `CCTest`, `SubscriberTest`,
  `BindingDispatchTest`, `CapabilityHandlerTest` (with `DaprServerTestBase` as a shared helper).
- **Integration tests** (require Docker): `scala-cli test . --test-only 'dapr4s.test.integration.*'`.
  They use `testcontainers-scala-munit` with the `TestContainersForAll` pattern and exercise a real
  Dapr sidecar. `startContainers()` **must return an already-started container** — call `c.start()`
  before returning; the framework does NOT auto-start it. Tests use `withContainers { c => }`. The
  `DaprTestContainer` wrapper bridges the testcontainers-scala `SingleContainer` type to the Dapr
  Java testcontainers type. Actor, workflow, state, pub/sub, lock, secrets, and service-invocation
  capabilities each have a `*ServerTest` here; there are no in-process / mock-context actor tests.
- CI runs unit and integration tests as separate jobs and gates `publish` on **both** passing.
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

- Work on branches, not directly on `master`/`main`.
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, etc.
- When forking external repos for contribution proposals: fork via `gh repo fork`, create a
  branch with a descriptive name (`feat/blocking-client-virtual-threads`), implement, compile,
  push. Do not open a PR without being asked.
- Do not force-push, do not skip hooks, do not amend published commits.

---

## What we are NOT doing

- No effect library dependency (Kyo, Ox, Cats Effect, ZIO) — the capability-passing approach
  via `?=>` context functions is sufficient and keeps the user API minimal.
- No async/`Future`-based API — the library is synchronous and blocking, designed for virtual
  threads.
- No exposing Reactor/Mono types to users — all Reactor is confined to `internal/`.
- No client-side WorkflowCapability yet (starting/querying/terminating workflow instances) —
  server-side hosting (`Workflow`, `WorkflowActivity`, `WorkflowContext`) is implemented.
