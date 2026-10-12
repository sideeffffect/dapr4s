# dapr-wasm-components WIT vs dapr4s capabilities

> Sources: sideeffffect/dapr-wasm-components @ afa33c5, 2026-10-12; Rikito Taniguchi (VirtusLab), 2026-08-24
> Raw: [dapr-wasm-components WIT 0.6.0](../../raw/dapr/2026-10-12-dapr-wasm-components-wit-0.6.0.md); [Scala and Wasm Component Model](../../raw/scala-js/2026-08-24-virtuslab-scala-wasm-component-model.md)

## Overview

[dapr-wasm-components](https://github.com/sideeffffect/dapr-wasm-components) defines the WIT package
`dapr-wasm-components:interfaces@0.6.0`: 14 **synchronous** outbound interfaces (app imports, app → Dapr) and 7
`*-callback` inbound interfaces (app exports, Dapr → app). Provider components (wasi-http / wasi-grpc, outbound and
inbound) implement the wire. An app composes as `outbound → app → inbound` (acyclic). dapr4s maps onto this well:
both are blocking/synchronous, both are universal-by-name (every WIT function takes the store/component name first,
exactly like dapr4s's universal `XCapability`), and the error philosophy matches (expected misses are values,
infrastructure failures are not in the type). The main gaps are **workflow authoring** (no WIT callback exists) and
**the toolchain** (Scala.js → WASI component is only possible via the experimental `scala-wasm` fork).

## Structural fit

| Aspect | WIT | dapr4s | Fit |
|---|---|---|---|
| Call style | sync functions | blocking calls (virtual threads on JVM, JSPI on JS) | exact — on a component target no JSPI is needed |
| Name binding | name is the first param of every function | universal `XCapability` takes the name; `X.Instance` binds it | exact — `Instance` is pure Scala sugar |
| Payloads | `list<u8>` | `JsonCodec[T]` values | codec encodes to bytes at the wall |
| Expected failures | `result<_, variant>` (`key-not-found`, `etag-mismatch`, `not-acquired`) | `Option`, `Boolean`, `Option[ETagMismatchException]`, `UnlockStatus` | good — map variants to the existing shapes |
| Infra failures | provider **traps** | exceptions (`NonFatal`) | a trap cannot be caught in-component; dapr4s exceptions for those just never occur |
| Read-only tiers | none (WIT has no authority split) | `X.ReadOnly` / `X.Instance.ReadOnly` | dapr4s-only layer — no conflict |
| Inbound | exported callbacks, mandatory, dispatch by data | `DaprApp` routes list | good — one generic callback impl dispatches over `DaprApp` |

## Per building block (outbound)

| Block | WIT | dapr4s | Overlap / gaps |
|---|---|---|---|
| State | get, get-bulk, save (multi-item), delete, execute-transaction, query | get/getWithETag/getBulk/queryState, save/saveBulk/saveWithETag, delete/deleteWithETag, transaction | **~100 %**. WIT extras: per-item metadata, get-bulk `parallelism` |
| Pub/sub | publish, publish-bulk | publish, publishWithMetadata, bulkPublish | **~100 %** |
| Bindings | invoke-binding | invoke, invokeOneWay | **100 %** (oneWay = ignore response) |
| Secrets | get-secret (multi-value map), get-bulk-secret | get → `Option[SecretValue]`, getBulk | **high**. WIT secret is `list<tuple<string,string>>`; dapr4s `SecretValue` is one string (pick by key) |
| Configuration | get, subscribe → id, unsubscribe | get, subscribe(onChange) → `AutoCloseable` | **high**, but push is inverted: updates arrive on `configuration-callback.on-configuration-event`; dapr4s needs an id → closure registry |
| Invocation | invoke(app-id, path, verb, headers, query, body) → status/headers/body | invoke(appId, method, data, httpMethod, metadata) → `Resp` | **high**. WIT is richer (status, response headers, query); dapr4s exposes only the decoded body |
| Lock | try-lock, unlock | tryLock → Boolean, unlock → `UnlockStatus` | **~100 %**. WIT has `lock-belongs-to-others`; dapr4s `UnlockStatus` has no such case (only Success/LockNotFound/InternalError) |
| Workflow (client) | start, get, terminate, raise-event, pause, resume, purge (needs `workflow-component`) | start/startWithId, getStatus, waitForCompletion, suspend/resume, terminate, raiseEvent, purge | **high**. `waitForCompletion` = poll `get`; WIT `workflow-instance` has no typed output (properties map) |
| Jobs | schedule (failure policy), get, delete | schedule/scheduleOnce, get, delete (JVM only) | **high** — and it would bring jobs to Scala.js-on-Wasm |
| Crypto | encrypt/decrypt + streaming resources | encrypt/decrypt(+String) | **dapr4s ⊂ WIT** — streaming unused |
| Conversation | converse (tools, usage, context) | converse (JVM only) | **high** — also comes to the component target |
| Actors (client) | invoke + get-state, state txn, reminders, timers | invoke/invokeVoid via `ActorCapability` | invoke **100 %**; state/reminder/timer ops back `ActorContext` instead |
| Runtime | metadata, labels, healthz | — | **WIT only** |

## Per building block (inbound)

| Callback | dapr4s source | Notes |
|---|---|---|
| invocation-callback.on-invoke | `DaprApp.invokeRoutes` | dispatch on `method` |
| pubsub-callback | `DaprApp.subscriptions` | dead-letter matches `@deadLetter`; WIT adds CEL routing rules and bulk subscribe (dapr4s has neither); `topic-event-response` = `SubscriptionResult` (Success/Retry/Drop) exactly |
| bindings-callback | `DaprApp.bindings` | WIT response can also write state / fan out; dapr4s uses only data |
| jobs-callback | `DaprApp.jobs` | direct |
| actors-callback | `DaprApp.actors` | config/on-invoke/on-timer/on-reminder/deactivate map to `ActorDefinition` + `ActorContext` (state via outbound `actors` interface). HTTP app channel only |
| configuration-callback | subscribe closures | see Configuration above |
| health-callback | none (server answers itself) | trivial default |
| **(missing)** workflow + activities | `DaprApp.workflows` / `activities` | **no WIT interface** — Dapr workflows run over the durabletask gRPC streaming protocol, not the app channel. Workflow authoring cannot run on this target until a `workflow-callback` (or a durabletask-in-wasm executor) exists |

## Toolchain status (2026-10)

- Upstream Scala.js Wasm output **needs a JS host**; it cannot be a WASI component.
- The **scala-wasm** fork (`io.github.scala-wasm`, VirtusLab) adds `moduleKind = WasmComponent` plus a `wit-bindgen
  scala` fork that generates Scala bindings from WIT. sbt plugin only; upstreaming goes via Minimal Wasm
  ([scala-js#5333](https://github.com/scala-js/scala-js/issues/5333)) first.
- Hosts: GC + exception-handling components need **Spin canary** or a recent wasmtime; SpinKube / Akamai not yet.
- JS-dependent libraries do not link (`@JSImport`, `js.Dynamic`). For dapr4s that excludes the whole `src/js/`
  layer (`@dapr/dapr`, express, ScalablyTyped, `js.async`/JSPI) — the component target needs its own `internal`
  wall built on the generated WIT bindings.

## See Also

- [Dapr JS SDK](dapr-js-sdk.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Scala.js async / JSPI / Wasm](../scala-js/scala-js-async-jspi-wasm.md)
- [Cross-Building JVM + Scala.js with Scala CLI](../scala-js/scala-js-cross-building-scala-cli.md)
