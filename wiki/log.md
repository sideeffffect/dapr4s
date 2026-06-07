# Wiki Log

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
