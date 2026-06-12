# Wiki Log

## [2026-06-12] note | JVM/JS integration-test coverage parity (cross-build rework)
- Context: closed the two asymmetric gaps where a shared capability was integration-tested on only one platform — added test/jvm/integration/ConfigurationCapabilityServerTest (JVM twin of the JS configuration suite: configuration.redis on a shared Docker network, `redis-cli MSET` seeding of `value||version`) and test/js/integration/CryptoJsIntegrationTest (JS twin of the JVM crypto suite: crypto.dapr.localstorage, RSA key generated per-run by scripts/js-integration-env.sh into the git-ignored scripts/js-it/keys/, gRPC-only path like the configuration suite). Now every JS-supported capability is integration-tested on both platforms; jobs/conversation remain JS-absent at compile time (not untested), and bindings is the lone shared capability with only derivation+unit coverage on both (symmetric).
- The component-config mechanism stays platform-idiomatic on purpose: JVM declares components in-code via testcontainers-dapr `withComponent(Component(...))`, JS mounts daprd component YAMLs — there is no scripts/jvm-it/components/ because testcontainers-dapr writes those files for you. Equivalence is in content (same component types, same seeding), not a shared source. Recorded in docs/DESIGN.md "Integration-test coverage parity".

## [2026-06-12] ingest | self-contained _sjs1_3 artifact: outputPackage rename + compileOnly deps + facade embedding
- Raw sources: the dapr4s self-contained-artifact rework itself — scripts/generate-st-facades.sh (`--outputPackage dapr4styped`, digest churn), scripts/embed-st-facades.sh (cs-fetch-resolved transitive set → class/tasty/sjsir staging), js-deps.scala (compileOnly.dep + scalablytyped-runtime/scalajs-dom regular deps), converter CLI sources @ 1.0.0-beta45 (Main.scala `Name(x)`: outputPackage is a single identifier; dotted values backtick-escape) — no new raw files; the script headers and js-deps.scala comments are the canonical record
- Updated: scala-js/scalablytyped-with-scala-cli.md (the published-library consumer problem now has the implemented answer: outputPackage rename — single-identifier constraint, collision rationale, digest-relevant; `compileOnly.dep` verified on scala-cli 1.14 — platform-scoped like `using dep`, absent from the published POM entirely, not even `provided`; publish-time embedding via `--resource-dirs`; consumer proof method — publish local, hide ~/.ivy2/local/org.scalablytyped, compile+link+run a consumer to a Dapr-level error; previous ship-the-recipe approach demoted to alternatives)

- Removed the index row for `scala3-language/scala-cli-build-tool.md` (article was never present on
  disk — lost before the first commit) and redirected the `java-interop-safe-scala.md` See Also link
  to `scala-js/scala-js-cross-building-scala-cli.md`, which now covers the same ground.

## [2026-06-12] ingest | ScalablyTyped-with-scala-cli pipeline + JS test-harness field notes (dapr4s cross-build rework)
- Raw sources: the dapr4s cross-build rework itself — scripts/generate-st-facades.sh + js-deps.scala + package.json (converter pipeline), scripts/test-js-integration.sh + wasm-test.sh + scripts/js-it/ hooks + test/js/integration/ (harness findings, 8 suites/26 tests on Wasm+JSPI vs live daprd 1.17 + redis), node_modules @dapr/dapr 3.18.0 task-hub-grpc-worker.js — no new raw files; the script headers, js-deps.scala comments, and suite comments are the canonical record
- Created: scala-js/scalablytyped-with-scala-cli.md (converter CLI 1.0.0-beta45 with scala-cli: flag landmines — `--scala 3` resolves 3.7.3 and breaks std, pin 3.3.6; full --scalajs version; -s es2022 skips the broken dom stdlib; typescript npm package required — @types/* as top-level deps, deterministic npmVersion-digest coordinates from package-lock + converter tuple, ivy2Local zero-config resolution + CI cache paths, ESM gotchas (deep-module values unresolvable, CJS default-export shim), MutableBuilder option traits + TS-vs-wire mismatch patterns, the org.scalablytyped-not-on-Central consumer problem + options)
- Updated: scala-js/scala-js-cross-building-scala-cli.md (CORRECTION: plain `using dep` IS platform-scoped by a same-file target.platform directive — the leak is `test.dep`-only, `.test.scala`-filename workaround; replaced the jvm-deps.scala/--exclude pattern with the target.platform-scoped deps files pattern; added: no --include exists — positional re-include of excluded files is silently ignored)
- Updated: scala-js/scala-js-async-jspi-wasm.md (new munit-on-Wasm+JSPI harness notes: js.async{...}.toFuture per test + raw-js.Promise vacuous-pass footgun; scala-cli wasm DirectoryNotEmptyException-after-green-run cleanup bug + wrapper pattern; the plain-JS linker WEDGES on orphan-await test sources; scala-cli runs node from PATH with zero V8 flags → Node 25 floor; ESM resolution-hook pattern for npm deps in test runs; --test-only ineffective on the JS runner; UUID.randomUUID does not link — SecureRandom absent)
- Updated: dapr/dapr-js-sdk.md (task-hub-grpc-worker isFirstAttempt bug — the first stream error of an *established* worker still rethrows as a first-attempt failure, so a daprd restart permanently kills the workflow worker, reconnect never happens; upstream issue candidate. Redis integer-etag behaviour — fabricated non-numeric etag → 400 ERR_STATE_SAVE, a genuine 409 conflict needs a stale real etag. jobs/conversation note updated to dapr4s's compile-time absence)

## [2026-06-11] ingest | Scala.js implementation learnings (dapr4s port field notes)
- Raw sources: the dapr4s Scala.js cross-build implementation itself (src/internal/js/ + facades), runtime-verified against node_modules @dapr/dapr 3.18.0 + express 4.22.2 and a live daprd sidecar — no new raw files; the verified findings live in the code's scaladocs (Express.scala, DaprCapabilityImpl.scala, WorkflowCoroutine.scala, WorkflowContextImpl.scala)
- Updated: scala-js/scala-js-async-jspi-wasm.md (new "Field notes from the dapr4s port" section: JSImport.Default is the one correct binding for CJS default-export modules under both module kinds; the per-request js.async re-entry pattern in express handlers; the AsyncGenerator-from-coroutine recipe with the strict-alternation safety argument)
- Updated: dapr/dapr-js-sdk.md (GrpcEndpoint scheme bug — `grpc://` renders the channel target `grpc:host:port`, which grpc-js cannot resolve; bare host or `https://` are the only working spellings, and setTls only honours `https:`/`?tls=true`; orchestration-executor generator-driving details — next/throw only, never return, yields must be instanceof the vendored Task, post-done next() happens; deterministic-UUID gap — no newUuid in the JS SDK, mirror the Java SDK's v5/SHA-1 algorithm with namespace 9e952958-5e33-4daf-827f-2fa12937b875)

## [2026-06-11] ingest | Scala.js Cross-Build Research (scala-js topic) + Dapr JS SDK
- Raw sources (scala-js): empirical scala-cli 1.12.2/1.14.0 cross-platform probes (/tmp/sjs-probe et al.) + scala-cli.virtuslab.org docs/releases/issues; scala-js.org release notes 1.17.0–1.21.0 + WebAssembly backend docs + JSPI.scala + chromestatus/nodejs/synckit/cats-effect; empirical CC-on-Scala.js probe (/tmp/cc-js-probe, dapr4s nightly 3.10.0-RC1-bin-20260607-dec42ae) + scala/scala3 Compiler.scala/issue tracker
- Raw sources (dapr): dapr/js-sdk source survey @ a3be700 (= @dapr/dapr 3.18.0) + npm registry + v3.17.0/v3.18.0 release notes
- Created topic `scala-js`: scala-js-cross-building-scala-cli.md, scala-js-async-jspi-wasm.md, capture-checking-on-scala-js.md
- Created: dapr/dapr-js-sdk.md
- Updated: dapr/dapr-java-sdk.md (See Also cross-reference to the JS SDK)
- NOTE: context is the dapr4s Scala.js cross-build (direct-style API preserved via Wasm+JSPI orphan js.await; @dapr/dapr as the JS substrate). Pre-existing index issue spotted (not fixed here, ingest not lint): scala3-language/scala-cli-build-tool.md is referenced by index.md but missing on disk.

## [2026-06-07] ingest | Scala 3 Metaprogramming + Trait-to-Implementation Derivation (RPC)
- Raw sources (scala3-metaprogramming): docs.scala-lang.org/scala3/reference/metaprogramming {index, inline, compiletime-ops, macros, reflection, staging, tasty-inspect}; scala-hearth.readthedocs.io
- Created topic `scala3-metaprogramming`: metaprogramming-overview.md, inline.md, compile-time-operations.md, macros-quotes-and-splices.md, tasty-reflection.md, runtime-staging-and-tasty-inspection.md, scala-hearth.md
- Raw sources (scala-rpc-derivation): source-level survey of GitHub repos — cornerman/sloth, automorph-org/automorph, Kalin-Rudnicki/Oxygen, outr/spice, reactivecore/kreuzberg, neandertech/smithy4s-deriving, bishabosha/ops-mirror, zio/zio-blocks#1270, typelevel/cats-tagless, goodcover/tagless-redux, zio/zio (IsReloadable), 7mind/izumi (distage TraitConstructor); plus a multi-source landscape survey
- Created topic `scala-rpc-derivation`: trait-to-impl-derivation-overview.md, derivation-mechanism-pattern.md, and per-library articles sloth/automorph/oxygen-http/spice/kreuzberg/smithy4s-deriving/ops-mirror/zio-blocks-rpc/cats-tagless/tagless-redux/zio-isreloadable/distage-traitconstructor (all tiers; Mirror/typeclass-derivation path covered via ops-mirror + smithy4s-deriving)
- NOTE: excluded (with reasons recorded in the overview) — scala-json-rpc, autowire, mu-scala, Lagom (Scala 2 macros); airframe-rpc, jsonrpclib, smithy4s core, scalapb, Caliban client (external codegen); endpoints4s, tapir, sttp (no macros / value+interpreter)

## [2026-05-05] lint | 0 issues found, 0 auto-fixed
- All internal links valid (51 wiki articles checked)
- All Raw field references valid
- Index complete — all articles indexed
- Heuristic finding: `kubernetes/local-kubernetes-stacks.md` could cross-reference `testing/testcontainers-overview.md` as a lighter alternative to k8s for Dapr integration testing (not auto-fixed)

## [2026-05-05] ingest | Testcontainers Overview and Testcontainers-Scala
- Raw sources: testcontainers.com; java.testcontainers.org (wait strategies, networking, reuse, configuration); testcontainers/testcontainers-scala GitHub + usage/setup docs; yadukrishnan.live; jasondl.ee inter-container networking; deepwiki Ryuk docs; testcontainers.com/modules/dapr; docs.dapr.io Spring Boot integration
- Created: testing/testcontainers-overview.md (Ryuk, wait strategies, startup strategies, port mapping, networking, reuse, configuration)
- Created: testing/testcontainers-scala.md (SBT setup, lifecycle traits, ContainerDef/Container, GenericContainer, MUnit, DockerComposeContainer, 40+ modules, networking)
- Updated: dapr/dapr-testcontainers.md (added multi-language support section, Spring Boot @ServiceConnection pattern, cross-references to testing articles)

## [2026-05-02] ingest | k3d v5.x Changes and Dapr Dev-Mode Redis Hostname
- Updated: kubernetes/local-kubernetes-stacks.md (k3d v5.8.3 version, v5.x breaking changes, dev-mode Redis hostname)
- Updated: kubernetes/dapr-on-kubernetes.md (corrected Redis hostname dapr-dev-redis-master, added lock.redis component example)

## [2026-05-02] ingest | Local Kubernetes Stacks; Dapr on Kubernetes
- Created: kubernetes/local-kubernetes-stacks.md
- Created: kubernetes/dapr-on-kubernetes.md

## [2026-05-02] ingest | Scala Best Practices (nrinaudo) — complete reference

## [2026-05-02] ingest | Parse, Don't Validate; Primitive Obsession and Opaque Types; ADTs and Making Illegal States Unrepresentable

## [2026-05-02] ingest | Dapr Java SDK — Virtual Threads (extended: injection point survey)
- Updated: Dapr Java SDK

## [2026-05-01] ingest | Effect Systems Overview
- Updated: Direct-Style Effects
- Updated: Capability-Based Effects
- Updated: Capabilities for Safe Agents

## [2026-05-01] ingest | Direct-Style Effects
- Raw sources: Noel Welsh "Direct-style Effects Explained"; Nicolas Rinaudo "Controlling Program Flow with Capabilities"; Nicolas Rinaudo "Effects as Capabilities"

## [2026-05-01] ingest | Capability-Based Effects
- Raw sources: Nicolas Rinaudo "Effects as Capabilities"; "The right(?) way to work with capabilities"; "Hands on Capture Checking"; "Controlling Program Flow with Capabilities"

## [2026-05-01] ingest | Capabilities for Safe Agents
- Raw sources: Odersky, Zhao, Xu, Bračevac, Pham — "Tracking Capabilities for Safer Agents" (arXiv 2603.00991)

## [2026-05-01] ingest | Capture Checking Overview
- Raw sources: Scala 3 official docs — overview.md, basics.md, cc.md, classes.md, safe.md, checked-exceptions.md, classifiers.md, polymorphism.md, advanced.md, scoped-capabilities.md, mutability.md, separation-checking.md, how-to-use.md, internals.md
- Created: capture-checking-overview.md, capturing-types.md, capabilities-and-resources.md, safe-exceptions.md, separation-and-mutability.md, capability-classifiers.md, safe-mode.md, how-to-use.md

## [2026-05-01] ingest | Scala 3 Language Features (scala3-language topic)
- Raw sources: scala-cli.virtuslab.org deps guide; scala-cli.virtuslab.org using-directives guide; scala-cli.virtuslab.org directives reference; docs.scala-lang.org opaque types book; rockthejvm.com opaque types article; docs.scala-lang.org context functions reference; blog.softwaremill.com context-is-king (FETCH FAILED — SSL error, placeholder raw file); docs.scala-lang.org given/using book; docs.scala-lang.org givens reference; virtuslab.com safe-scala-introduction; softwaremill.com callbacks-structured-concurrency-scala
- Created: opaque-types.md, context-functions-capability-passing.md, given-using.md, scala-cli-build-tool.md, java-interop-safe-scala.md

## [2026-05-01] ingest | Kyo Effects, Ox Structured Concurrency, Effekt Capability Passing, Scala Caps Capability, Scala Effect Libraries Comparison
- Raw sources: github.com/getkyo/kyo README; virtuslab.com/blog comparing Kyo/Gears/Ox (Łukasz Biały, 2026-04-15); github.com/softwaremill/ox README; softwaremill.com understanding-capture-checking-in-scala (Adam Warski); github.com/b-studios/scala-effekt README (discontinued); Cambridge UP Effekt paper (Brachthäuser/Schuster/Ostermann, 2020); effekt-lang.org overview; scala-lang.org api scala.caps.Capability; alexn.org scala-gamble-with-direct-style (Alexandru Nedelcu, 2025-08-29)
- Created: scala-effect-libraries/kyo-effects.md, scala-effect-libraries/ox-structured-concurrency.md, scala-effect-libraries/effekt-capability-passing.md, scala-effect-libraries/scala-caps-capability.md, scala-effect-libraries/scala-effect-libraries-comparison.md

## [2026-05-01] ingest | Dapr Overview
- Raw sources: docs.dapr.io concepts, building blocks, components, sidecar, security; github.com/dapr/java-sdk README + source; dapr/java-sdk testcontainers
- Created: dapr-overview.md, dapr-building-blocks.md, dapr-service-invocation.md, dapr-state-management.md, dapr-pub-sub.md, dapr-actors.md, dapr-workflows.md, dapr-java-sdk.md, dapr-testcontainers.md, dapr-other-building-blocks.md

## [2026-05-01] ingest | Dapr Resiliency
- Raw sources: docs.dapr.io/operations/resiliency/resiliency-overview; docs.dapr.io/operations/resiliency/policies
- Created: dapr-resiliency.md

## [2026-05-01] ingest | Dapr Workflow Patterns
- Raw sources: docs.dapr.io/developing-applications/building-blocks/workflow/workflow-patterns; raw.githubusercontent.com/dapr/docs v1.15 workflow-patterns.md
- Created: dapr-workflow-patterns.md
- Updated: dapr-workflows.md (see also cross-reference)

## [2026-05-01] ingest | Dapr Actors Deep Dive
- Raw sources: diagrid.io/blog/understanding-dapr-actors-for-scalable-workflows-and-ai-agents
- Created: dapr-actors-deep-dive.md
- Updated: dapr-actors.md (see also cross-reference)

## [2026-05-01] ingest | Dapr Pluggable Components
- Raw sources: docs.dapr.io/developing-applications/develop-components/pluggable-components/pluggable-components-overview
- Created: dapr-pluggable-components.md

## [2026-05-01] ingest | Gears Async (EPFL)
- Raw sources: github.com/lampepfl/gears README + source (Async.scala, futures.scala, channels.scala, API docs); lampepfl.github.io/gears; natsukagami.github.io/gears-book
- Created: scala-effect-libraries/gears-async.md
- Updated: scala-effect-libraries/scala-effect-libraries-comparison.md (cross-reference added via See Also)

## [2026-05-01] ingest | testcontainers-dapr javadoc
- Raw sources: javadoc.io/doc/io.dapr/testcontainers-dapr/latest (403, partial); github.com/diagridio/testcontainers-dapr DaprContainer.java, DaprPlacementContainer.java, DaprContainerTest.java, DaprComponentTest.java
- Updated: dapr/dapr-testcontainers.md (added: diagridio groupId distinction, withAppChannelAddress, Testcontainers.exposeHostPorts, 4-param withSubscription, withComponent(Path), QuotedBoolean, default auto-provisioned components/subscriptions, configure() internals, DaprPlacementContainer details, no readiness wait rationale)

## [2026-05-01] ingest | Reach Capabilities, CC Calculus, Scoped Capabilities, Polymorphic Reachability Types, Algebraic Effects and Handlers
- Raw sources: arXiv:2509.07609 "What's in the Box" (Xu, Bračevac, Pham, Odersky — OOPSLA 2025); arXiv:2105.11896 "Capturing Types" preprint (Boruch-Gruszecki, Odersky, Lee, Lhoták, Brachthäuser — TOPLAS 2023); arXiv:2207.03402 "Scoped Capabilities for Polymorphic Effects" (Odersky et al. 2022); arXiv:2307.13844 "Polymorphic Reachability Types" (Wei, Bračevac et al. 2023); Plotkin & Pretnar "Handlers of Algebraic Effects" (ESOP 2009)
- Created: reach-capabilities.md, cc-calculus.md, scoped-capabilities-polymorphic-effects.md, polymorphic-reachability-types.md, algebraic-effects-handlers.md

## [2026-05-01] ingest | Dapr Java Client Lifecycle
- Raw sources: docs.dapr.io/developing-applications/sdks/java/java-client
- Raw file created: raw/dapr/2026-05-01-dapr-java-client-lifecycle.md
- NOTE: dapr-java-sdk.md already covers DaprClient construction (DaprClientBuilder), AutoCloseable/try-with-resources, Mono/Flux reactive model, .block() for synchronous use, DaprPreviewClient, and DaprException — no update to existing article required

## [2026-05-01] lint | 12 issues found, 9 auto-fixed

## [2026-06-13] lint | 4 issues found, 3 auto-fixed
- Fixed: index entry scala3-language/scala-cli-build-tool.md marked [MISSING] (no such file)
- Fixed: orphan dapr-e2e-selfhosted.md — added back-reference from dapr-testcontainers.md See Also
- Fixed: orphan scala-best-practices-nrinaudo.md — added back-references from adts-illegal-states.md, parse-dont-validate.md, primitive-obsession-opaque-types.md
- Reported (not fixed): broken link java-interop-safe-scala.md:187 → scala-cli-build-tool.md (article missing; raw scala-cli sources exist but no compiled article)
