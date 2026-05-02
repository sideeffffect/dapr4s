# AGENTS.md — scala-safe-dapr

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

Refer to `DESIGN.md` for the full architecture and to `SPEC.allium` for the formal specification.
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
    pure by default; strengthens `DaprRuntime.run`'s body type.
  - `-language:experimental.modularity` — enables `tracked` keyword for singleton-typed
    constructor parameters.
  - `-experimental` — enables clause interleaving (`def f[A](x: A)[B: TC]: B`).
  - `-Ycc-verbose`, `-Yexplicit-nulls`, `-Wconf:any:error` (fatal warnings).
- **Dependencies**: upickle 3.3.1 (pinned — 4.x crashes on CC-annotated types), munit 1.3.0,
  testcontainers-scala-munit 0.44.1, testcontainers-dapr 1.17.2.

---

## Code style and correctness rules

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

For best throughput, callers should invoke `DaprRuntime.run` from a virtual thread:
- Plain Scala `main()`: `Thread.ofVirtual().start(() => DaprRuntime.run { ... }).join()`
- Spring Boot 3.2+: `spring.threads.virtual.enabled=true`
- Quarkus: `@RunOnVirtualThread`
- Helidon 4: virtual threads by default

### Comments
Only write comments when the *why* is non-obvious: a hidden constraint, a subtle invariant, a
workaround for a specific library bug, or behavior that would surprise a reader. When something
warrants a comment, make it thorough — explain the reasoning fully, not just the action. See the
`InterruptedException` handling in `MonoOps` and the `NonFatal` trade-off note in `DaprRuntime`
as style references.

### Experimental Scala features — exploit them, don't just enable them
- **`pureFunctions`**: `DaprRuntime.run`'s body type is `(DaprScope, CanThrow[Exception]) ?=> T`
  — a pure context function. Demonstrate this with tests that show pure lambdas compose
  correctly and the body cannot close over external capabilities.
- **`modularity` + `tracked`**: `DaprScopeImpl` has `tracked private[internal] val client`.
  Each scope instance carries a distinct refined client type.
- **Clause interleaving**: `invoke` methods use `def invoke[Req: JsonCodec](...)[Resp: JsonCodec]`
  so `Req` is inferred from the data argument and `Resp` is specified at the call site.

### Java interop boundary
Everything in `src/internal/` is marked `@scala.caps.assumeSafe`. This is the only place Java
SDK types may appear. Nothing from the Java SDK (`Mono`, `DaprClient`, `GrpcChannel`, proto
classes, etc.) may appear in `src/` files outside `internal/` or in any test file. The
`@assumeSafe` boundary is the wall.

---

## Testing

- **Unit tests** (no Docker required): `scala-cli test . --test-only "*unit*"`. Currently 110
  tests across `JsonCodecTest`, `ModelsTest`, `StateCapabilityTest`, `CCTest`.
- **Integration tests** (require Docker): use `testcontainers-scala-munit` with the
  `TestContainersForAll` pattern. `startContainers()` creates but does not start the container —
  the framework calls `start()` in `beforeAll()`. Tests use `withContainers { c => }`. The
  `DaprTestContainer` wrapper bridges the testcontainers-scala `SingleContainer` type to the
  Dapr Java testcontainers type.
- After every non-trivial change: compile first, then run unit tests, then integration tests if
  relevant. Do not batch large changes and test only at the end.

---

## Allium spec and DESIGN.md

`SPEC.allium` is the formal specification of the library's behaviour written in the Allium DSL.
`DESIGN.md` is the architecture and design document with Mermaid diagrams.

Both must stay in sync with the code. If you change the code, update the spec and design doc.
If you change the spec or design doc, update the code to match. Verify Mermaid diagrams are
valid and renderable. The Allium spec is maintained using the `allium:distill` and `allium:elicit`
skills.

---

## Wiki

The project has a wiki at `wiki/` maintained by the `karpathy-llm-wiki` skill. Topics covered:
Safe Scala / capture checking, effect systems, DAPR and DAPR Java SDK internals, Scala effect
libraries (Kyo, Ox, Gears), research papers on capability-based type systems.

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
- No actors or workflow building blocks yet — the core building blocks (state, pub/sub,
  invocation, secrets, config, bindings, lock) are the current scope.
