# dapr4s — Design

## Goal

A Scala 3 library that exposes every DAPR building block as a **tracked capability**. User code compiles under `import language.experimental.safe` and `import language.experimental.captureChecking`. The underlying Dapr SDK is completely hidden — users see only Scala types.

**Requires**: Scala `3.10.0-RC1-bin-20260607-dec42ae-NIGHTLY` (or later nightly). The `import language.experimental.safe` and `import language.experimental.captureChecking` features are fully supported in nightly builds — no `-Ycc` flag is needed.

**Platforms**: JVM and Scala.js, with a byte-identical public API (modulo the platform-trait surface, see below). On the JVM the internal layer wraps the Dapr **Java SDK**; on Scala.js it wraps the Dapr **JS SDK** (`@dapr/dapr`). Both SDKs are confined behind the same `@assumeSafe` wall (`src/jvm/internal/` and `src/js/internal/` respectively) — see the [Scala.js platform](#scalajs-platform) section. Sources are organised as `src/{shared,jvm,js}` and `test/{shared,jvm,js}` (see Project Structure).

Each DAPR effect (state access, pub/sub, service calls, secrets, configuration, bindings) is tracked as a captured capability via `^` annotations. The Scala 3 compiler statically verifies:

- Which DAPR effects a computation may perform.
- That DAPR resources cannot escape their managed scope.
- That agent-generated code cannot introduce new unsafe capabilities.

---

## Two-Layer Architecture

```mermaid
graph TB
    subgraph "User Code (safe mode)"
        UC["User function\nimport language.experimental.safe\nimport language.experimental.captureChecking"]
    end

    subgraph "Public API (capability traits)"
        DS["DaprCapability (root capability)"]
        SC["StateCapability^scope"]
        PC["PublishCapability^scope"]
        IC["InvokeCapability^scope"]
        SEC["SecretsCapability^scope"]
        CC["ConfigurationCapability^scope"]
        BC["BindingsCapability^scope"]
        LC["LockCapability^scope"]
    end

    subgraph "Public API — subscriber side"
        DA["DaprApp (case class)\n+ Subscription / InvokeRoute / BindingRoute"]
    end

    subgraph "Internal Layer — JVM (@assumeSafe boundaries, src/jvm/internal/)"
        DR["Dapr(config).run { ... }"]
        DS2["Dapr(config).serve { ... }"]
        IMPL["*CapabilityImpl\n(non-safe-mode,\n@assumeSafe methods)"]
        DC["DaprClient\n(Java SDK)"]
        SRV["DaprAppServer(DaprApp)\n(OpenJDK HttpServer)"]
    end

    subgraph "DAPR Sidecar"
        SID["SidecarConfig.httpEndpoint\n(default http://localhost:3500)\nSidecarConfig.grpcEndpoint\n(default http://localhost:50001)"]
    end

    UC -->|"summon[DaprCapability].state.instance(...)"| DS
    DS --> SC & PC & IC & SEC & CC & BC & LC
    SC & PC & IC & SEC & CC & BC & LC -->|"implemented by"| IMPL
    IMPL -->|"DaprClient.*().toFuture().get()"| DC
    DC -->|"HTTP/gRPC"| SID

    DR -->|"provides DaprCapability ?=>"| UC
    DS2 -->|"body returns DaprApp"| DA
    DS2 --> SRV
    DA -->|"passed to constructor"| SRV
    SRV -->|"GET /dapr/subscribe\nPOST /<route>"| SID
```

On Scala.js, everything above the internal layer is the **same source code**; only the internal layer is swapped for a JS twin (package `dapr4s.internal`, sources in `src/js/internal/`):

```mermaid
graph TB
    subgraph "Shared across platforms (identical sources)"
        UC2["User code (safe mode)"]
        API2["Public API: capability traits, DaprApp,\nopaque types, derivation macros"]
    end

    subgraph "Internal Layer — Scala.js (@assumeSafe boundaries, src/js/internal/)"
        JR["Dapr(config).run / serve\n(src/js/Dapr.scala)\n+ JS-only runAsync / serveAsync"]
        JIMPL["*CapabilityImpl JS twins\n(JsAwait: orphan js.await over js.Promise)"]
        JDC["DaprClient (JS SDK, HTTP protocol)\n+ lazy gRPC DaprClient\n+ lazy DaprWorkflowClient"]
        JFETCH["raw sidecar HTTP via fetch\n(actor client, ActorContext)"]
        JSRV["DaprAppServer twin\n(express 4, js.async per request)"]
        JWF["WorkflowRuntime (JS SDK)\n+ WorkflowCoroutine\n(AsyncGenerator bridge)"]
    end

    subgraph "DAPR Sidecar"
        SID2["HTTP :3500 / gRPC :50001"]
    end

    UC2 --> API2
    API2 -->|"implemented by"| JIMPL
    JR -->|"provides DaprCapability ?=>"| UC2
    JIMPL --> JDC
    JIMPL --> JFETCH
    JR --> JSRV
    JSRV --> JWF
    JDC -->|"HTTP/gRPC"| SID2
    JFETCH -->|"HTTP"| SID2
    JSRV -->|"app channel"| SID2
    JWF -->|"gRPC work-item stream"| SID2
```

### Layer 1 — Public API (safe-mode-compatible)

Capability traits (one package `dapr4s.<family>` per building block, see Capability Hierarchy), opaque domain types, and the `Dapr(config).run` / `.serve` entry points (on `class Dapr(config: DaprConfig)` — `src/jvm/Dapr.scala` on the JVM, `src/js/Dapr.scala` on Scala.js, same public signatures). These compile cleanly under both safe mode and capture checking. No Java or JS SDK types are visible.

### Layer 2 — Internal implementations (`@assumeSafe`)

Non-safe-mode Scala that wraps the platform SDK calls — the Java SDK's `DaprClient` in `src/jvm/internal/`, the JS SDK's `DaprClient`/`DaprWorkflowClient`/`WorkflowRuntime` (plus express and raw `fetch`) in `src/js/internal/`. Each class/object is marked `@scala.caps.assumeSafe` (from the `scala.caps` package) so safe-mode user code may call them through the capability trait interfaces. Library authors are responsible for the safety contract; user code cannot add new `@scala.caps.assumeSafe` annotations (the annotation is itself restricted to non-safe-mode code).

Note: safe mode is enabled **per-file** via `import language.experimental.safe` (not globally via a compiler flag). This is intentional: files in `internal/` (both platforms) and `jvm/Dapr.scala`/`js/Dapr.scala`/`JsonCodec.scala` must use `@scala.caps.assumeSafe` and therefore cannot have the safe-mode import.

---

## Capability Hierarchy

Each building block is one **capability family**. A family has a principal **universal** capability that is not bound to any name: `StateCapability` reaches any state store, so each of its methods takes the `storeName`; `InvokeCapability` reaches any app, so each call takes the `appId`. The root `DaprCapability` has one argument-less member per building block that returns the universal capability (`dapr.state: StateCapability^{this}`). The universal capability can be held and passed around as it is, or narrowed. All the narrower interfaces live in the companion object of the universal capability, and there are two axes of narrowing:

- **By name.** `instance(name)` returns `X.Instance`, which is bound to one store / component / app / job / workflow instance. It carries the name as a `val` (`storeName`, `pubsubName`, `appId`, `jobName`, `instanceId`, …), so its methods do not take the name again: `dapr.state.instance(storeName).save(key, value)`.
- **By authority.** Five families have a read-only subset: State, Secrets, Configuration, Jobs and Workflow. Each of them also has `X.ReadOnly` (a super-trait of `X`) and `X.Instance.ReadOnly` (a super-trait of `X.Instance`). You narrow the authority with a plain upcast. `X.ReadOnly.instance(name)` returns `X.Instance.ReadOnly`, and `X` overrides `instance` covariantly to return `X.Instance`. So the two narrowings commute: when you narrow a read-only capability by name, you get a read-only instance, never a full one.

Actor has two levels of names: `ActorCapability` (any actor) → `forType(actorType)` → `ActorCapability.Type` (one actor type) → `instance(actorId)` → `ActorCapability.Instance` (one actor). The design and the alternatives that were rejected are in [CAPABILITY-SPLITS.md §11](CAPABILITY-SPLITS.md#11-the-implemented-design--universal-capability--instance--readonly-tiers).

| Family (package `dapr4s.<family>`) | Universal capability | `instance(name)` → `Instance` | `ReadOnly` / `Instance.ReadOnly` members | Members only in the full tiers |
|---|---|---|---|---|
| `state` | `StateCapability` | `StateStoreName` | `get`, `getWithETag`, `getBulk`, `queryState` | `save`, `saveBulk`, `saveWithETag`, `delete`, `deleteWithETag`, `transaction` |
| `secrets` | `SecretsCapability` | `SecretStoreName` | `get` | `getBulk` |
| `configuration` | `ConfigurationCapability` | `ConfigurationStoreName` | `get` | `subscribe` |
| `jobs` (JVM only) | `JobsCapability` | `JobName` | `get` | `schedule`, `scheduleOnce`, `delete` |
| `workflow` | `WorkflowCapability` | `WorkflowInstanceId` | `getStatus`, `waitForCompletion` | `suspend`, `resume`, `terminate`, `raiseEvent`, `purge`; universal only: `start`, `startWithId` |
| `publish` | `PublishCapability` | `PubSubName` | — | `publish`, `publishWithMetadata`, `bulkPublish` |
| `invoke` | `InvokeCapability` | `AppId` | — | `invoke` (with or without a body), `invokeHttp` |
| `bindings` | `BindingsCapability` | `BindingName` | — | `invoke`, `invokeOneWay` |
| `lock` | `LockCapability` | `LockStoreName` | — | `tryLock`, `unlock` |
| `crypto` | `CryptoCapability` | `CryptoComponentName` | — | `encrypt`, `decrypt`, `encryptString`, `decryptString` |
| `conversation` (JVM only) | `ConversationCapability` | `ConversationComponentName` | — | `converse` |
| `actor` | `ActorCapability` | `ActorType` → `Type`, then `ActorId` → `Instance` | — | `invoke`, `invokeVoid` |

The universal capability has every method of its `Instance`, with the name as an extra first parameter (`StateCapability.save(storeName, key, value)` vs. `StateCapability.Instance.save(key, value)`). The `getBulk`/`saveBulk` state operations use the DAPR batch APIs (`getBulkState` / `saveBulkState`) — one sidecar call each.

```mermaid
classDiagram
    class DaprCapability {
        <<trait>>
        +state StateCapability^this
        +publish PublishCapability^this
        +invoke InvokeCapability^this
        +secrets SecretsCapability^this
        +configuration ConfigurationCapability^this
        +bindings BindingsCapability^this
        +lock LockCapability^this
        +actor ActorCapability^this
        +workflow WorkflowCapability^this
        +crypto CryptoCapability^this
        +jobs JobsCapability^this
        +conversation ConversationCapability^this
    }
    note for DaprCapability "Root capability. Each member returns the family's universal capability.\nCompanion transformers: DaprCapability.state(name) { ... } puts\nStateCapability.Instance in scope. jobs + conversation come from the JVM\nplatform trait DaprCapabilityPlatform; on Scala.js they do not exist."

    class StateRO["StateCapability.ReadOnly"] {
        <<trait>>
        +instance(storeName) StateCapability.Instance.ReadOnly^this
        +get[T](storeName, key, consistency) Option[T]
        +getWithETag[T](storeName, key, consistency) StateEntry[T]
        +getBulk[T](storeName, keys) Map[StateStoreKey,StateEntry[T]]
        +queryState[T](storeName, query) List[StateEntry[T]]
    }
    class State["StateCapability"] {
        <<trait>>
        +instance(storeName) StateCapability.Instance^this
        +save[T](storeName, key, value) Unit
        +saveBulk[T](storeName, entries) Unit
        +saveWithETag[T](storeName, key, value, etag) Option[ETagMismatchException]
        +delete(storeName, key) Unit
        +deleteWithETag(storeName, key, etag) Option[ETagMismatchException]
        +transaction(storeName, ops) Unit
    }
    class StateInstRO["StateCapability.Instance.ReadOnly"] {
        <<trait>>
        +storeName: StateStoreName
        +get[T](key, consistency) Option[T]
        +getWithETag[T](key, consistency) StateEntry[T]
        +getBulk[T](keys) Map[StateStoreKey,StateEntry[T]]
        +queryState[T](query) List[StateEntry[T]]
    }
    class StateInst["StateCapability.Instance"] {
        <<trait>>
        +save[T](key, value) Unit
        +saveBulk[T](entries) Unit
        +saveWithETag[T](key, value, etag) Option[ETagMismatchException]
        +delete(key) Unit
        +deleteWithETag(key, etag) Option[ETagMismatchException]
        +transaction(ops) Unit
    }
    note for StateRO "Same four-interface shape for Secrets, Configuration,\nJobs and Workflow (see the table above)"

    class X["XCapability"] {
        <<trait>>
        +instance(name: N) XCapability.Instance^this
        +op(name: N, ...) R
    }
    class XInst["XCapability.Instance"] {
        <<trait>>
        +name: N
        +op(...) R
    }
    note for X "Two-interface shape for Publish, Invoke, Bindings, Lock,\nCrypto and Conversation (no read-only subset):\nN = PubSubName, AppId, BindingName, LockStoreName,\nCryptoComponentName, ConversationComponentName"

    class Actor["ActorCapability"] {
        <<trait>>
        +forType(actorType) ActorCapability.Type^this
        +invoke[Req,Resp](actorType, actorId, method, data) Resp
        +invoke[Resp](actorType, actorId, method) Resp
        +invokeVoid(actorType, actorId, method) Unit
    }
    class ActorTypeCap["ActorCapability.Type"] {
        <<trait>>
        +actorType: ActorType
        +instance(actorId) ActorCapability.Instance^this
        +invoke[Req,Resp](actorId, method, data) Resp
        +invoke[Resp](actorId, method) Resp
        +invokeVoid(actorId, method) Unit
    }
    class ActorInst["ActorCapability.Instance"] {
        <<trait>>
        +actorType: ActorType
        +actorId: ActorId
        +invoke[Req,Resp](method, data) Resp
        +invoke[Resp](method) Resp
        +invokeVoid(method) Unit
    }

    class DaprApp {
        <<case class>>
        +subscriptions: List[Subscription]
        +invokeRoutes: List[InvokeRoute]
        +bindings: List[BindingRoute]
        +workflows: List[Workflow]
        +activities: List[WorkflowActivity[?,?]]
        +actors: List[ActorDefinition]
        +jobs: List[JobRoute]
        +\+\+(other: DaprApp) DaprApp
    }

    StateRO <|-- State : extends
    StateInstRO <|-- StateInst : extends
    DaprCapability --> State : .state
    State --> StateInst : instance(storeName)
    StateRO --> StateInstRO : instance(storeName)
    DaprCapability --> X : .publish / .invoke / .bindings / .lock / .crypto / .conversation
    X --> XInst : instance(name)
    DaprCapability --> Actor : .actor
    Actor --> ActorTypeCap : forType(actorType)
    ActorTypeCap --> ActorInst : instance(actorId)
```

**Companion forwarders.** The companion object of **every** interface forwards **all** of that interface's methods, inherited ones included, from a `using` capability. So business logic never names the capability value: `StateCapability.save(storeName, key, value)`, `StateCapability.Instance.save(key, value)`, `StateCapability.Instance.ReadOnly.get[T](key)`, `WorkflowCapability.start(name)`, `WorkflowCapability.Instance.suspend()`, `ActorCapability.Instance.invokeVoid(method)`. Each companion whose trait has an `instance(name)` (or, for actors, `forType(actorType)`) narrowing method also has a same-named accessor that narrows the capability in scope and **returns** it, captured by that capability: `X.instance(name)` (`using X` → `X.Instance^{cap}`), `X.ReadOnly.instance(name)` (`using X.ReadOnly` → `X.Instance.ReadOnly^{cap}`), and for actors `ActorCapability.forType(actorType)` (→ `Type`), `ActorCapability.instance(actorType, actorId)` and `ActorCapability.Type.instance(actorId)` (→ `Instance`). The `DaprCapability` companion transformers start from the root: `DaprCapability.state/publish/secrets/configuration/bindings/lock/crypto/conversation(name) { ... }` and `DaprCapability.actor(actorType, actorId) { ... }` put the **`Instance`** into scope, while `DaprCapability.invoke { ... }`, `DaprCapability.workflow { ... }` and `DaprCapability.jobs { ... }` put the **universal** capability into scope (the target app / workflow instance / job is chosen per call).

**Derivation uses the narrowest tier.** The derivation macros require only the tier that the derived member uses: derived State/Secrets/Configuration getters need only `X.Instance.ReadOnly` (a full `Instance` also satisfies them, because it is a subtype), derived State setters need `StateCapability.Instance`, derived jobs getters need only `JobsCapability.ReadOnly`, and the Invoke, Workflow and Jobs derivations call the universal capability (`InvokeCapability`, `WorkflowCapability`, `JobsCapability`) directly, because the target name is part of each call.

**Packages.** Each capability family lives in its own package `dapr4s.<family>` (`dapr4s.state`, `dapr4s.publish`, `dapr4s.invoke`, `dapr4s.secrets`, `dapr4s.configuration`, `dapr4s.bindings`, `dapr4s.lock`, `dapr4s.actor`, `dapr4s.workflow`, `dapr4s.crypto`, `dapr4s.jobs`, `dapr4s.conversation`), which holds the universal capability trait (with its `ReadOnly` / `Instance` / `Instance.ReadOnly` tiers in its companion), the family's opaque names/IDs and models, its inbound route type (`Subscription`, `InvokeRoute`, `BindingRoute`, `JobRoute`, actor route types) and its derivation macros. The root `dapr4s` package keeps the cross-cutting surface: `DaprCapability`, `Dapr`, `DaprConfig`, `DaprApp`, `JsonCodec`, `HttpMethod`, the exceptions and the cross-cutting opaque types (`AppId`, `MetadataKey`/`MetadataValue`, `Route`, `SerializedJson`, …). There is deliberately **no re-export facade** — re-exported opaque types resolve inconsistently in macro quote/construction contexts — so consumers `import dapr4s.state.*` etc. for the families they use.

---

## Subscriber Server (`Dapr(config).serve`)

Apps that need to receive Dapr events (pub/sub messages, input binding triggers, service invocations) use `Dapr(config).serve` instead of `run`.  The body returns a declarative [[DaprApp]] value describing all inbound routes; the runtime passes it to `DaprAppServer` which starts an HTTP server on `config.appServer.port` (default 8080) and blocks until interrupted.

```mermaid
sequenceDiagram
    participant App
    participant Dapr
    participant DaprAppServer
    participant Sidecar

    App->>Dapr: Dapr(config).serve { ... returns DaprApp }
    Note over App: body builds DaprApp with\nSubscription / InvokeRoute / BindingRoute
    Dapr->>DaprAppServer: new DaprAppServer(body)
    Dapr->>DaprAppServer: startAndBlock(config.appServer.port)
    Note over DaprAppServer: builds dispatch tables\nfrom DaprApp fields
    DaprAppServer-->>Sidecar: (server ready on app port)
    Sidecar->>DaprAppServer: GET /dapr/subscribe
    DaprAppServer-->>Sidecar: JSON subscription list
    Sidecar->>DaprAppServer: POST /topic-route (CloudEvent)
    DaprAppServer->>App: handler(CloudEvent[T])
    App-->>DaprAppServer: SubscriptionResult.Success
    DaprAppServer-->>Sidecar: {"status":"SUCCESS"}
```

The server runs each request on its own virtual thread (via `Executors.newVirtualThreadPerTaskExecutor()`).  Handler lambdas may capture capabilities from the `serve` body to make outbound calls (e.g., saving received messages to state).  Two `DaprApp` values may be combined with `++` to compose service modules.

### CloudEvent envelope parsing

The Dapr sidecar wraps pub/sub messages in CloudEvents.  `DaprAppServer` extracts the `data` field and decodes it with the registered `JsonCodec[T]`.  If decoding fails, the result is `SubscriptionResult.Drop` (silently discards — avoids poison-pill retry loops).  If the handler itself throws, the result is `SubscriptionResult.Retry`.

A `Subscription` may declare an optional `deadLetterTopic: Option[Topic]`; when set it is emitted as `deadLetterTopic` in the `/dapr/subscribe` response so the sidecar routes undeliverable messages to that topic instead of dropping them.

### Service-invocation routes

`InvokeRoute.apply` builds a route whose handler receives only the decoded request body (`Q => R`).  `InvokeRoute.withRequest` is an HTTP-method-aware overload whose handler receives the full `InvokeRequest[Q]` envelope (method name, `httpMethod: HttpMethod`, decoded body), letting one route branch on the incoming HTTP verb.

### Route collision

Pub/sub routes (default `/<topic>`), input binding paths (`/<bindingName>`), service invocation paths (`/<methodName>`), and job-trigger paths (`/job/<jobName>`) all use the same flat namespace.  Users should choose distinct names.  Registration is first-writer-wins.

### Job triggers

A scheduled job created via `JobsCapability.schedule`/`scheduleOnce` (`dapr.jobs.schedule(JobName(...), ...)` or `dapr.jobs.instance(JobName(...)).schedule(...)`) is delivered by the sidecar as a POST to `/job/<jobName>` on the app server. A `JobRoute[T](JobName(...)) { payload => ... }` registered on the `DaprApp` decodes the payload (the sidecar sends either the raw value or a `{"data": ...}` envelope — both are accepted) and invokes the handler. Unknown job names return 404; a handler exception returns 500.

> **`ExclusiveCapability` as a cornerstone**: Every capability in this library — `DaprCapability`, every interface of every capability family (the universal `X`, `X.ReadOnly`, `X.Instance`, `X.Instance.ReadOnly`, and `ActorCapability.Type`), `ActorContext`, and `WorkflowContext` — extends `scala.caps.ExclusiveCapability`. This is intentional and load-bearing:
> - `ExclusiveCapability` tells the Scala 3 CC framework that these objects are exclusive (not shared), enabling separation checking that prevents concurrent or escaping use.
> - `scala.caps.Capability` is **sealed** in nightly Scala 3; only `SharedCapability` and `ExclusiveCapability` can be extended. `SharedCapability` would allow sharing across concurrent fibers, which violates the exclusive-per-invocation semantics we require.
> - Because each sub-capability extends `ExclusiveCapability`, CC infers `^{fresh}` for new instances returned from methods. The trait declares `^{this}` to bind the sub-capability lifetime to the parent — overrides must explicitly annotate return types as `StateCapability^{this}` / `StateCapability.Instance^{this}` etc. to satisfy the override check. See the JVM and JS `DaprCapabilityImpl` (and the `*UniversalImpl` classes next to it) for the pattern.
> - `@scala.caps.assumeSafe` on implementation classes suppresses CC checks inside the method body, but override-level compatibility checks still fire — hence the explicit `^{this}` annotations are required in every `DaprCapabilityImpl` and `*CapabilityImpl`.

---

## Handler Implementation Pattern (Capability-as-Effect-System)

Handler objects follow a two-layer structure that maximises capability tracking while containing the CC escape hatches in a single place.

### Layer 1 — Business logic methods

Pure handler methods declared with **anonymous** `using` capability parameters.  Business logic calls **companion-object methods** on the capability types (`StateCapability.Instance.save(...)`, `PublishCapability.Instance.publish(...)`) rather than naming the capability value — the compiler resolves the implicit from the anonymous `using` context:

```scala
def placeOrder(req: OrderRequest)(using StateCapability.Instance, PublishCapability.Instance): OrderResponse =
  val orderId = java.util.UUID.randomUUID().toString
  StateCapability.Instance.save(StateStoreKey(orderId), req)
  PublishCapability.Instance.publish(OrdersTopic, OrderEvent(orderId, req.item, req.quantity))
  OrderResponse(orderId, "accepted")
```

Each capability interface has a companion object that mirrors every method of the interface (inherited ones included) as a static forwarder taking `using cap: <that interface>`. A handler asks for the narrowest tier it needs — e.g. `using StateCapability.Instance.ReadOnly` for a lookup — and the full tiers satisfy it, because they are subtypes.  This makes the call-site type act as both a static effect declaration (which capabilities are required) and as a namespace for the API:

```scala
// src/shared/state/StateCapability.scala
object StateCapability:
  object Instance:
    def save[T: JsonCodec](key: StateStoreKey, value: T)(using cap: Instance): Unit =
      cap.save(key, value)
    def get[T: JsonCodec](
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    )(using cap: Instance): Option[T] =
      cap.get(key, consistency)
    // ... all other methods of Instance (and Instance.ReadOnly)
  // ... and the same for StateCapability itself, StateCapability.ReadOnly and StateCapability.Instance.ReadOnly
```

These methods carry no `@assumeSafe`; they are pure capability-tracked code that the compiler can reason about.

### Layer 2 — the `*App` object's `apply` method: declarative route description

The promoted idiom is a dedicated `*App` object whose `apply` method takes the capabilities it needs (`using DaprCapability`, codecs, …) and returns an immutable `DaprApp` — the in-library analogue of a `main`.  It uses the **`DaprCapability` transformer API** to nest sub-capabilities into scope.  Each `DaprCapability.xxx(...)` call acquires a sub-capability and makes it available as an implicit inside its body block.  Handler methods are passed as direct function references — no wrapping lambda is needed because no library method carries a `throws T` annotation:

```scala
object OrderServiceApp:
  def apply()(using DaprCapability): DaprApp =
    DaprCapability.state(StateName) {
      DaprCapability.publish(PubSubComp) {
        DaprApp(
          invokeRoutes = List(
            InvokeRoute[OrderRequest, OrderResponse](InvokeMethodName("place-order"))(placeOrder),
            InvokeRoute[String, Option[OrderRequest]](InvokeMethodName("get-order"))(getOrder)
          )
        )
      }
    }
```

Transformer signature (in `src/shared/DaprCapability.scala`):
```scala
object DaprCapability:
  def state(storeName: StateStoreName)[T](body: StateCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.state.instance(storeName).asInstanceOf[StateCapability.Instance])
```

`cap.state.instance(storeName)` is the universal `StateCapability` followed by its `instance`, which narrows it to `StateCapability.Instance`.

**Capability injection lifetime**: Capabilities are bound once per `apply` call and shared across all handler invokeRoutes for the lifetime of the `DaprCapability` scope.  The CC type system ensures they cannot outlive the scope (`ScopeContainment` invariant).

**`DaprApp` stores handlers as `AnyRef`**: Handler lambdas capture DAPR capabilities.  `Subscription`, `InvokeRoute`, and `BindingRoute` store them as `rawHandler: AnyRef` (CC-opaque) so the instances have an empty capture set and can live in a plain `List`.  Internal dispatch code (`DaprAppServer`, `TestDaprApp`) casts them back via path-dependent types under `@assumeSafe`.

### Diagram

```mermaid
graph LR
    subgraph "*App object (no @assumeSafe)"
        BL["Business logic def methods\nusing StateCapability.Instance\nusing PublishCapability.Instance\n→ calls StateCapability.Instance.save/get/...\n→ calls PublishCapability.Instance.publish/..."]
        CFG["apply()\nusing DaprCapability\nreturns DaprApp"]
        CFG -->|"DaprCapability.state(name) { ... }\nDaprCapability.publish(name) { ... }"| BL
    end

    subgraph "DaprApp (immutable, declarative)"
        DA["DaprApp\nList[Subscription]\nList[InvokeRoute]\nList[BindingRoute]"]
    end

    subgraph "Library internals (@assumeSafe)"
        SRV["DaprAppServer(app)\nbuilds dispatch tables\nfrom DaprApp"]
        TST["TestDaprApp\ncall / deliver"]
    end

    CFG -->|"InvokeRoute(BL-method)"| DA
    DA -->|"passed to constructor"| SRV
    DA -->|"passed to test helpers"| TST
```

### Testing with `TestDaprApp`

Integration tests use the `TestDaprApp` object (which is `@assumeSafe` internally) to invoke handlers directly against a `DaprApp` without an HTTP round-trip.  Tests point `Dapr` at the Testcontainers sidecar by constructing a `DaprConfig` whose `SidecarConfig.httpEndpoint` / `grpcEndpoint` come from the container (a test-only `Dapr.runWithEndpoints(http, grpc)` extension wraps this — it is **not** part of the published library):

```scala
Dapr(DaprConfig(SidecarConfig(httpEndpoint = c.httpEndpoint, grpcEndpoint = c.grpcEndpoint))).run:
  val scope = summon[DaprCapability]
  val app   = OrderServiceApp()(using scope)
  val resp  = TestDaprApp.call[OrderRequest](app, "place-order", OrderRequest("widget", 2))[OrderResponse]
```

Two apps can be composed with `++`:
```scala
val combined = OrderServiceApp() ++ InventoryServiceApp()
```

---

## Opaque Domain Types

All domain identifiers are opaque to prevent accidental misuse (e.g., passing a `PubSubName` where a `StateStoreName` is expected).

| Type | Wraps | Non-empty? | Purpose |
|---|---|---|---|
| `StateStoreName` | `String` | yes | DAPR state store component name |
| `LockStoreName` | `String` | yes | DAPR lock store component name |
| `PubSubName` | `String` | yes | DAPR pub/sub component name |
| `Topic` | `String` | yes | Pub/sub topic |
| `AppId` | `String` | yes | Target application ID for service invocation |
| `SecretStoreName` | `String` | yes | DAPR secrets store component name |
| `ConfigurationStoreName` | `String` | yes | DAPR configuration store component name |
| `BindingName` | `String` | yes | DAPR output binding component name |
| `InvokeMethodName` | `String` | yes | Service-invocation / inbound handler method name |
| `ActorMethodName` | `String` | yes | Actor method name |
| `Route` | `String` | yes | HTTP route for a pub/sub subscription |
| `BindingOperation` | `String` | yes | Operation name for an output binding |
| `LockResourceId` | `String` | yes | Resource identifier for a distributed lock |
| `LockOwner` | `String` | yes | Lock owner identifier |
| `ActorType` | `String` | yes | Dapr virtual actor type name |
| `ReminderName` | `String` | yes | Persistent actor reminder name |
| `TimerName` | `String` | yes | Non-persistent actor timer name |
| `WorkflowName` | `String` | yes | Dapr workflow class name |
| `EventName` | `String` | yes | External event name for `WorkflowContext.waitForExternalEvent` and `WorkflowCapability.raiseEvent` / `WorkflowCapability.Instance.raiseEvent` |
| `CryptoComponentName` | `String` | yes | DAPR cryptography component name |
| `CryptoKeyName` | `String` | yes | Key name within a crypto component |
| `KeyWrapAlgorithm` | `String` | yes | Key-wrap algorithm (e.g. `RSA`, `AES`); has `Rsa`/`Aes` constants |
| `JobName` | `String` | yes | DAPR job name (routed back to `/job/<name>`) |
| `ConversationComponentName` | `String` | yes | DAPR conversation (LLM) component name |
| `ETag` | `String` | no | Optimistic-concurrency tag |
| `StateStoreKey` | `String` | no | Key in a DAPR state store (`StateCapability` family) |
| `ActorStateKey` | `String` | no | Key in a virtual actor's state |
| `StateQuery` | `String` | no | State store query expression (JSON filter) |
| `SecretKey` | `String` | no | Key in a DAPR secrets store |
| `ConfigurationKey` | `String` | no | Key in a DAPR configuration store |
| `BulkEntryId` | `String` | no | Caller-assigned ID for bulk-publish correlation |
| `WorkflowInstanceId` | `String` | no | Dapr workflow instance ID |
| `ActorId` | `String` | no | Dapr virtual actor instance ID |
| `HttpMethod` | enum | — | HTTP verb used by an incoming service invocation |

Each opaque type lives in its own file (one type per file): family-specific ones in their family package directory (`src/shared/state/StateStoreName.scala` → `dapr4s.state.StateStoreName`, `src/shared/jobs/JobName.scala` → `dapr4s.jobs.JobName`, …), cross-cutting ones (`AppId`, `Route`, `MetadataKey`/`MetadataValue`, `SerializedJson`, `ApiToken`, `DaprPort`, `DaprDuration`, `PemPath`, `ContentType`) in `src/shared/optypes/` (package `dapr4s`). Smart constructors validate non-empty constraints at construction time (non-empty types). Extension methods provide `.value` unwrapping.

---

## Value Types

Structured data without identity, compared by value. Defined per family in `src/shared/<family>/<Family>Models.scala` (`StateModels`, `PublishModels`, `InvokeModels`, `ConfigurationModels`, `LockModels`, `WorkflowModels`; `HttpMethod` stays in the root `src/shared/Models.scala`) — except the jobs and conversation models (`JobSchedule`, `JobDetails`, `ConversationMessage` and friends), which are JVM-only surface and live in `src/jvm/jobs/JobsModels.scala` / `src/jvm/conversation/ConversationModels.scala`. These correspond to the `value` and `entity` declarations in the spec's Value Types and Entities sections.

| Type | Scala form | Purpose |
|---|---|---|
| `StateEntry[T]` | `case class` | Result of a state fetch; holds `value: Option[T]` and `etag: Option[ETag]` |
| `ConfigurationItem` | `case class` | Single configuration item: key, value, version, metadata |
| `ConfigurationUpdate` | `case class` | Config update notification from sidecar: `storeName: ConfigurationStoreName`, `items: Map[ConfigurationKey, ConfigurationItem]` |
| `BulkPublishEntry[T]` | `case class` | Entry in a bulk publish request: `entryId: BulkEntryId`, `event: T` |
| `BulkPublishResult` | `case class` | Result of a bulk publish: `failedEntries: List[BulkEntryId]` |
| `UnlockStatus` | `enum` | Result of a distributed lock unlock: `Success`, `LockNotFound`, `LockBelongsToOthers`, `InternalError` |
| `SubscriptionResult` | `enum` | What a pub/sub handler returns to sidecar: `Success`, `Retry`, `Drop` |
| `CloudEvent[T]` | `case class` | Incoming CloudEvent from sidecar: envelope fields + `data: T` |
| `InvokeRequest[T]` | `case class` | Incoming service invocation: `methodName`, `httpMethod: HttpMethod`, `data: T` |
| `InvokeResponse` | `case class` | `invokeHttp` result: `status: Int`, `headers: Map[MetadataKey, MetadataValue]` (lower-case names), `body: ArraySeq[Byte]` |
| `HttpMethod` | `enum` | HTTP verb: `Get`, `Post`, `Put`, `Patch`, `Delete`, `Head`, `Options` |
| `StateOp` | `sealed abstract class` | Base of the state transaction ADT (see below) |
| `WorkflowSnapshot` | `case class` | Snapshot of a workflow instance's current state |
| `WorkflowStatus` | `enum` | Workflow instance lifecycle status |
| `JobSchedule` | `enum` | Job schedule: `Cron(expr)`, `Every(period)`, `Daily`/`Hourly`/`Weekly`/`Monthly`/`Yearly` |
| `JobDetails` | `case class` | Stored job definition returned by `JobsCapability.get` / `JobsCapability.Instance.get` |
| `ConversationMessage` | `case class` | A conversation message with `role: ConversationMessageRole` + text; smart constructors `user`/`system`/`assistant`/`developer`/`tool` |
| `ConversationMessageRole` | `enum` | Conversation message role: `System`, `User`, `Assistant`, `Tool`, `Developer` |
| `ConversationTool` / `ConversationToolCall` | `case class` | Tool (function) definition and an assistant's tool-call request |
| `ConversationResponse` | `case class` | Result of `converse`: `outputs: List[ConversationResult]` (choices + usage) |

### StateOp — sealed ADT (entity + variants in spec)

The spec models `StateOp` as an `entity StateOp` (base) with two variants:
- `variant UpsertOp : StateOp` — carries `key`, optional `etag`, and `encoded_value` (pre-encoded JSON string)
- `variant DeleteOp : StateOp` — carries `key` and optional `etag`

In Scala this is represented as:

```scala
sealed abstract class StateOp          // entity StateOp
object StateOp:
  final case class UpsertOp(key: StateStoreKey, encodedValue: SerializedJson, etag: Option[ETag]) extends StateOp
  final case class DeleteOp(key: StateStoreKey, etag: Option[ETag] = None)                        extends StateOp
```

`UpsertOp.encodedValue` is a `SerializedJson` opaque type (structurally always present, non-nullable by construction). Use the companion `UpsertOp.apply[T]` smart constructor to encode a typed value immediately; this avoids type erasure issues when the operation is dispatched in `StateCapability.transaction`.

---

## JSON Serialization

User types must provide a `JsonCodec[T]` given instance. The library ships default instances for primitives and common collections. Users derive instances via upickle's `ReadWriter` derivation or supply custom ones.

```scala
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String | Null): Either[JsonDecodeException, T]

object JsonCodec:
  given JsonCodec[String] = ...
  given JsonCodec[Int]    = ...
  def decodeOrThrow[T: JsonCodec](json: String | Null): T   -- throws JsonDecodeException (unchecked)
  given [T: upickle.default.ReadWriter]: JsonCodec[T] = ...
```

**Raw-bytes outbound payloads**: pub/sub publish and output bindings encode the value with the `JsonCodec[T]` and hand the result to the Java SDK as **raw bytes** (`byte[]`), not as a `String`. The SDK's serializer passes `byte[]` through untouched but would re-serialize a `String`, double-encoding the JSON into a JSON-string. Exchanging bytes in both directions keeps dapr4s the sole owner of the JSON encoding. See `PublishCapabilityImpl` and `BindingsCapabilityImpl`. Service invocation bypasses the SDK entirely (raw `/v1.0/invoke` HTTP, see Error Handling): the typed `invoke` sends the UTF-8 bytes of the encoded JSON with `Content-Type: application/json`.

---

## Resource Lifecycle (Scope Safety)

`Dapr(config).run` builds a `DaprClient` from `config.sidecar` (HTTP/gRPC endpoints, timeouts, TLS, retries), creates a `DaprCapability` that captures the client reference, and releases it on exit. The `ActorClient` and `DaprWorkflowClient` are created lazily on first use and all clients are closed in the `finally` block. The return type of capabilities created inside `run` captures `DaprCapability`, so they cannot outlive the `run` block.

```mermaid
sequenceDiagram
    participant App
    participant Dapr
    participant DaprCapability
    participant DaprClient
    participant Sidecar

    App->>Dapr: Dapr(config).run { body }
    Dapr->>DaprClient: DaprClientBuilder()...build()  (endpoints from config.sidecar)
    Dapr->>DaprCapability: new DaprCapabilityImpl(client, ...)
    Dapr->>App: body (given DaprCapability)
    App->>DaprCapability: .state.instance("my-store")
    DaprCapability-->>App: StateCapability.Instance^scope
    App->>DaprCapability: state.save("k", value)
    DaprCapability->>DaprClient: saveState(...).block()
    DaprClient->>Sidecar: HTTP PUT /v1.0/state/my-store
    Sidecar-->>DaprClient: 200 OK
    DaprClient-->>DaprCapability: ()
    DaprCapability-->>App: ()
    App->>Dapr: (body complete)
    Dapr->>DaprClient: close()
```

---

## State Machine: StateCapability.Instance

```mermaid
stateDiagram-v2
    [*] --> Ready: DaprCapability.state(storeName) called

    Ready --> Fetching: get(key)
    Fetching --> Ready: Option value returned
    Fetching --> Error: DaprException

    Ready --> Saving: save(key, value)
    Saving --> Ready: ()
    Saving --> Error: DaprException

    Ready --> Deleting: delete(key)
    Deleting --> Ready: ()
    Deleting --> Error: DaprException

    Ready --> InTransaction: transaction(ops)
    InTransaction --> Ready: all ops applied
    InTransaction --> Error: DaprException (all ops rolled back)

    Error --> [*]: exception propagates to caller

    note right of Ready
        Capability bound to DaprCapability
        Cannot escape the run block
    end note
```

---

## Error Handling

Expected outcomes are **values**, not exceptions: a missing state key is `None`, an ETag conflict is `Some(ETagMismatchException)` (returned by `saveWithETag`/`deleteWithETag`, never thrown), lock outcomes are `Boolean`/`UnlockStatus`, a bulk-publish partial failure is a `BulkPublishResult`, a missing workflow instance is `None`, and `invokeHttp` returns a non-2xx answer as an `InvokeResponse`.

Everything the sidecar (or the path to it) reports as an **error** is a `dapr4s.DaprException` — a sealed hierarchy in `Exceptions.scala`, identical on the JVM and on Scala.js. No SDK exception type (`io.dapr.exceptions.DaprException`, `io.grpc.StatusRuntimeException`, a `js.JavaScriptException` wrapping an SDK error) reaches user code; the SDK exception is kept as `getCause` for logging only.

```mermaid
classDiagram
    class DaprException {
        <<sealed abstract>>
    }
    class ComponentNotFoundException {
        +componentKind: DaprComponentKind
        +name: String
    }
    class PermissionDeniedException
    class AppUnreachableException {
        +appId: AppId
    }
    class InvokeHttpException {
        +appId: AppId
        +method: InvokeMethodName
        +status: Int
        +body: ArraySeq[Byte]
    }
    class DaprApiException {
        +errorCode: String
    }
    DaprException <|-- ComponentNotFoundException
    DaprException <|-- PermissionDeniedException
    DaprException <|-- AppUnreachableException
    DaprException <|-- InvokeHttpException
    DaprException <|-- DaprApiException
```

| Exception | When | What the caller can do |
|---|---|---|
| `ComponentNotFoundException(kind, name)` | the named state store / pub/sub / secret store / configuration store / lock store / output binding / crypto / conversation component does not exist (or no component of that kind does). `DaprComponentKind` = `State`, `PubSub`, `Secrets`, `Configuration`, `Lock`, `Binding`, `Crypto`, `Conversation` | a deployment error: report it, fall back to another component |
| `PermissionDeniedException` | an access-control policy, a secret scope or a pub/sub topic scope denied the call | report, do not retry |
| `AppUnreachableException(appId)` | service invocation could not reach the target (unknown app id, target not healthy) | retry later, fail over |
| `InvokeHttpException(appId, method, status, body)` | the target app answered a **typed** `invoke` with non-2xx (3xx included) | branch on `status`, read `body` |
| `DaprApiException(errorCode, message)` | anything else, incl. `UNAVAILABLE` when the sidecar cannot be reached | log, propagate |

Plus two non-Dapr exceptions: `JsonDecodeException` (thrown by `JsonCodec.decodeOrThrow` when a payload does not match the expected type) and `ETagMismatchException` (returned, see above).

**One translation table, one translation point per wall.** The decision "which subtype" is the shared, platform-independent `internal.DaprErrors.translate(ctx, SidecarError, cause)`. Each wall only normalises its transport failure into a `SidecarError` (HTTP error code, `google.rpc.ErrorInfo` reason, HTTP status, gRPC status name, message):

- JVM: `JvmErrors.translate`, reached from `MonoOps.awaitResult(ctx)` (and `FluxOps.collectBytes(ctx)`) for every `Mono` SDK call, from `JvmErrors.guard(ctx)` for the synchronous `DaprWorkflowClient`, and from `JvmErrors.fromHttp` for the raw-HTTP paths (service invocation, `HttpActorContext`).
- Scala.js: `JsErrors.translate`, reached from `JsAwait.await(promise, ctx)` for every Dapr promise, from the SDK soft failures (`{error}` results of `state.save`/`state.delete`/`pubsub.publish`/`publishBulk`) and from `JsErrors.fromHttp` for the raw-`fetch` paths.

Every call site passes an `ErrorContext` — `Component(kind, name)`, `App(appId)` or `Runtime` (actors, workflows, jobs) — so the component name / app id in the exception comes from the **request**, never from parsing the sidecar's message. Matching is by Dapr error code or `ErrorInfo` reason first; legacy errors over gRPC carry neither (daprd sends only the status code and message), so the fallback compares the message with daprd's exact format string for that kind and name. HTTP statuses are deliberately not used for component errors (secret-store-not-found is 401, pubsub-not-found 404). The table, and the daprd v1.17 sources and live probe it comes from, are in `wiki/dapr/dapr-error-codes.md`.

`DaprApiException.errorCode` is the most specific code the transport carried (ERR code > ErrorInfo reason > gRPC status name > `UNKNOWN`), so it can differ between the JVM (gRPC) and Scala.js (HTTP) for the same failure; the typed subclasses are the cross-platform contract.

**Service invocation.** `invokeHttp` and the typed `invoke` both go to the sidecar's raw `/v1.0/invoke` HTTP API (`java.net.http` on the JVM, `fetch` on JS) — the Java SDK's `invokeMethod` throws on every non-2xx (3xx too) and drops the response headers, and the JS SDK cannot send falsy bodies and also drops headers. daprd passes the target app's status, headers and body through unchanged and reports its own failures as `{"errorCode":"ERR_DIRECT_INVOKE",...}`; a non-2xx answer is daprd's (→ `AppUnreachableException` / `PermissionDeniedException`) only when its body parses with `ERR_DIRECT_INVOKE` or a permission code, otherwise it is the app's answer. The typed `invoke` is the shared `InvokeSupport.invokeTyped` on top of `invokeHttp`: 2xx → decode, otherwise `InvokeHttpException` — the same code on both platforms. On the JVM the request timeout is `SidecarConfig.httpClientReadTimeout` (→ `DaprApiException("DEADLINE_EXCEEDED")`); GET/HEAD never carry a body.

`SecretsCapability.get` returns `None` when the store answers without the key (e.g. `secretstores.local.env` answers `{"k": ""}`); a store that reports a missing key as an error (`secretstores.local.file` answers 500 `ERR_SECRET_GET`) surfaces as `DaprApiException`.

Internal catch clauses use `scala.util.control.NonFatal` to ensure fatal JVM errors propagate immediately. `InterruptedException` is never translated or swallowed: `MonoOps.awaitResult` and the invoke HTTP client restore the interrupt flag and rethrow it unchanged.

---

## Project Structure (sbt)

Sources are split into `src/{shared,jvm,js}` and `test/{shared,jvm,js}`. The sbt build (`build.sbt`) points its projects into this layout rather than owning it: `coreJVM` compiles `src/{shared,jvm}`, `coreJS` `src/{shared,js}`, and the test trees are split into unit tests (core projects) and the `integration/` subdirectories (integration projects). Files under `jvm/`/`js/` still carry a `//> using target.platform` line from the scala-cli era; under sbt it is only a comment. Everything under `shared/` cross-compiles. Packages are independent of the platform directory (e.g. `src/jvm/internal/` and `src/js/internal/` are both package `dapr4s.internal`; `src/shared/jobs/` and `src/jvm/jobs/` are both `dapr4s.jobs`); below the platform directory, each capability family has its own subdirectory and package `dapr4s.<family>`.

```
dapr4s/
├── build.sbt                         # sbt build: coreJVM/coreJS (published dapr4s_3/dapr4s_sjs1_3 + unit
│                                     # tests), integrationJVM/integrationJS (Docker suites; JS on Wasm+JSPI),
│                                     # stFacades (ScalablyTyped facades, embedded into dapr4s_sjs1_3), POM
│                                     # metadata, nightly Scala + compiler flags, dependency versions
├── project/                          # build.properties (sbt version) + plugins.sbt (sbt-scalajs,
│                                     # crossproject, ScalablyTyped converter, sbt-ci-release, sbt-scalafmt,
│                                     # sbt-assembly)
├── .jvmopts                          # sbt JVM heap (the facade compile needs it)
├── package.json                      # npm pins for the JS layer: @dapr/dapr (runtime + converter input),
│                                     # @types/express + @types/node (converter inputs), testcontainers +
│                                     # @dapr/testcontainer-node (TEST-scope: JS integration sidecar + converter
│                                     # inputs), typescript (converter tool). Its `dependencies` are the
│                                     # conversion roots build.sbt reads.
├── package-lock.json                 # committed — the build installs exactly this (npm ci)
├── scripts/
│   ├── js-it/                        # Node ESM resolution hooks (node-resolve-hook.mjs + node-resolve-delegate.mjs)
│   ├── it/                           # canonical Dapr component files + secrets for both integration legs
│   └── k8s-test.sh                   # k3d end-to-end run (fat jars via coreJVM/Test/assembly)
├── src/
│   ├── shared/                       # cross-compiled sources (no platform directive)
│   │   ├── Models.scala              # HttpMethod (cross-cutting; family models live in their packages)
│   │   ├── JsonCodec.scala           # JsonCodec typeclass + default instances [@assumeSafe]
│   │   ├── DaprApp.scala             # DaprApp case class (aggregates the per-family route types)
│   │   ├── DaprCapability.scala      # DaprCapability trait: universal-capability members (extends
│   │   │                             # DaprCapabilityPlatform) + companion transformer API
│   │   │                             # (extends DaprCapabilityCompanionPlatform) [safe mode]
│   │   ├── DaprConfig.scala          # DaprConfig / SidecarConfig / AppServerConfig / ActorRuntimeConfig
│   │   ├── Validation.scala          # DaprAppValidationError + structural validation (validateOrThrow)
│   │   ├── Charsets.scala            # Charset constants/encoding helpers usable from safe-mode code
│   │   ├── Exceptions.scala          # ETagMismatchException, JsonDecodeException, DaprComponentKind,
│   │   │                             # sealed DaprException + ComponentNotFound/PermissionDenied/
│   │   │                             # AppUnreachable/InvokeHttp/DaprApi exceptions
│   │   ├── internal/                 # shared internal layer (package dapr4s.internal, no SDK types):
│   │   │                             # DaprErrors (ErrorContext, SidecarError, the error-translation table),
│   │   │                             # InvokeSupport (typed invoke on top of invokeHttp, URL encoding)
│   │   ├── optypes/                  # Cross-cutting opaque types, one per file, package dapr4s (AppId, Route,
│   │   │                             # MetadataKey/-Value, SerializedJson, ContentType, ApiToken, DaprPort,
│   │   │                             # DaprDuration, PemPath)
│   │   ├── state/                    # dapr4s.state: StateCapability (+ ReadOnly/Instance tiers), StateStoreName,
│   │   │                             # StateStoreKey, ETag, StateQuery, StateConsistency/-Concurrency,
│   │   │                             # StateModels (StateEntry, StateOp), State.derive
│   │   ├── publish/                  # dapr4s.publish: PublishCapability (+ Instance), PubSubName, Topic, CloudEvent*
│   │   │                             # ids, BulkEntryId, PublishModels, Subscription, Publish.derive,
│   │   │                             # Subscriptions.derive
│   │   ├── invoke/                   # dapr4s.invoke: InvokeCapability (+ Instance, val appId), InvokeMethodName,
│   │   │                             # InvokeModels (InvokeRequest, InvokeResponse, QueryParamName/-Value),
│   │   │                             # InvokeRoute, Invoke.derive,
│   │   │                             # InvokeRoutes.derive, InvokeDerivationRuntime
│   │   ├── secrets/                  # dapr4s.secrets: SecretsCapability (+ ReadOnly/Instance tiers), names/keys/values, Secrets.derive
│   │   ├── configuration/            # dapr4s.configuration: ConfigurationCapability (+ ReadOnly/Instance tiers), names/keys/values,
│   │   │                             # ConfigurationModels, Configuration.derive
│   │   ├── bindings/                 # dapr4s.bindings: BindingsCapability (+ Instance), BindingName,
│   │   │                             # BindingOperation, BindingRoute, Bindings.derive, BindingRoutes.derive
│   │   ├── lock/                     # dapr4s.lock: LockCapability (+ Instance), LockStoreName, LockResourceId,
│   │   │                             # LockOwner, LockModels (UnlockStatus)
│   │   ├── crypto/                   # dapr4s.crypto: CryptoCapability (+ Instance), names, KeyWrapAlgorithm,
│   │   │                             # Crypto.derive
│   │   ├── actor/                    # dapr4s.actor: ActorCapability (+ .Type / .Instance),
│   │   │                             # ActorType/ActorId/ActorMethodName/ActorStateKey/TimerName/ReminderName,
│   │   │                             # Actors (ActorContext, ActorDefinition, ActorRoutes + route types),
│   │   │                             # Actor.derive, ActorState.derive, ActorDefinitions.derive
│   │   ├── workflow/                 # dapr4s.workflow: WorkflowCapability (+ ReadOnly/Instance tiers),
│   │   │                             # WorkflowName/WorkflowInstanceId/ActivityName/EventName, WorkflowModels,
│   │   │                             # Workflows (Workflow + Workflow.derive, WorkflowActivity, ActivityDef,
│   │   │                             # Task, WorkflowContext), WorkflowActivities, WorkflowActivityCalls,
│   │   │                             # WorkflowEvents
│   │   ├── jobs/                     # dapr4s.jobs (cross-platform part): JobName, JobRoute, JobRoutes.derive
│   │   ├── conversation/             # dapr4s.conversation (cross-platform part): component/model/tool names
│   │   └── derivation/               # dapr4s.derivation — cross-cutting macro infrastructure only:
│   │                                 # MacroSupport, Forwarders (extends ForwardersPlatform), @name, annotations
│   ├── jvm/                          # [every file: target.platform jvm]
│   │   ├── Dapr.scala                # JVM entry point: class Dapr(config) with .run + .serve [@assumeSafe]
│   │   ├── DaprCapabilityPlatform.scala  # JVM platform surface: jobs + conversation members and
│   │   │                             # the companion transformer twins (the JS twin is empty)
│   │   ├── jobs/                     # dapr4s.jobs (JVM-only part): JobsCapability.scala (JobsCapability
│   │   │                             # + ReadOnly/Instance tiers), JobsModels.scala (JobSchedule, JobDetails),
│   │   │                             # Jobs.scala (Jobs.derive engine)
│   │   ├── conversation/             # dapr4s.conversation (JVM-only part): ConversationCapability.scala
│   │   │                             # (ConversationCapability + Instance), ConversationModels.scala
│   │   ├── PemPathJvm.scala
│   │   ├── derivation/
│   │   │   └── ForwardersPlatform.scala  # jobs runtime forwarders (the JS twin is empty)
│   │   └── internal/                 # JVM internal layer — Java SDK confined here
│   │       ├── DaprCapabilityImpl.scala
│   │       ├── MonoOps.scala         # Reactor Mono → blocking bridge (.toFuture().get()) + error translation
│   │       ├── JvmErrors.scala       # JVM wall's translation point: SDK/gRPC/HTTP failure → DaprException
│   │       ├── FluxOps.scala         # Reactor Flux subscription bridge (configuration subscribe)
│   │       ├── NullOps.scala / Json.scala
│   │       ├── DaprAppServer.scala   # HTTP server (OpenJDK jdk.httpserver); workflow/actor registration
│   │       ├── State/Publish/Invoke/Secrets/Configuration/Bindings/Lock/Actor/
│   │       │   Crypto/Jobs/Conversation/Workflow CapabilityImpl.scala
│   │       ├── HttpActorContext.scala
│   │       ├── WorkflowContextImpl.scala
│   │       └── WorkflowBridges.scala # WorkflowBridge / WorkflowActivityBridge (Java SDK adapters)
│   └── js/                           # [every file: target.platform scala-js]
│       ├── Dapr.scala                # Scala.js entry point: same public run/serve signatures
│       │                             # + JS-only runAsync/serveAsync [@assumeSafe]
│       ├── DaprCapabilityPlatform.scala  # deliberately EMPTY platform traits — no jobs/conversation on JS
│       ├── derivation/ForwardersPlatform.scala  # empty twin
│       └── internal/                 # Scala.js internal layer — @dapr/dapr confined here, via the
│           │                         # ScalablyTyped-generated dapr4styped.* facades (see Scala.js platform section)
│           ├── facade/ExpressModule.scala  # THE one hand-written facade: express CJS default-export shim
│           ├── JsAwait.scala         # THE orphan-js.await bridge (only home of allowOrphanJSAwait)
│           ├── JsErrors.scala        # JS wall's translation point: SDK/gRPC/fetch failure → DaprException
│           ├── JsInterop.scala       # JSON/string/error bridging (JS analogue of Json.scala + NullOps)
│           ├── DaprCapabilityImpl.scala  # + LazyClientRef, SidecarConfig → SDK options mapping
│           ├── State/Publish/Invoke/Secrets/Configuration/Bindings/Lock/Crypto
│           │   CapabilityImpl.scala  # twins (HTTP or gRPC client)
│           ├── ActorCapabilityImpl.scala       # actor client over raw sidecar HTTP (fetch)
│           ├── HttpActorContext.scala          # ActorContext over raw sidecar HTTP (fetch)
│           ├── WorkflowCapabilityImpl.scala    # workflow client over DaprWorkflowClient (gRPC)
│           ├── DaprAppServer.scala   # express-based app-channel server twin
│           ├── WorkflowHost.scala    # server-side workflow/activity hosting (WorkflowRuntime)
│           ├── WorkflowCoroutine.scala  # AsyncGenerator coroutine bridge (see Scala.js platform section)
│           └── WorkflowContextImpl.scala
└── test/
    ├── shared/                       # cross-compiled tests + fixtures
    │   ├── TestOptionCodec.scala
    │   ├── unit/                     # ModelsTest, JsonCodecTest, CharsetsTest, CCTest, DaprAppValidationTest,
    │   │                             # ActorDefinitionsTest, CapabilityDerivationTest, InvokeDerivationTest,
    │   │                             # ServerRouteDerivationTest, WorkflowActivityDerivationTest,
    │   │                             # WorkflowEventsTest, CapabilityHandlerTest, StateCapabilityTest (+ fixtures)
    │   ├── integration/              # SINGLE cross-platform IT suites (calls + assertions + munit registrations) that
    │   │                             # mix a per-platform fixture: State/Secrets/Lock/Crypto/Configuration ItTest +
    │   │                             # Order/Inventory/EndToEnd (via SharedDaprItSuite), and the server-delivery
    │   │                             # Invoke/Actor/PubSub/Workflow ItTest (via ServerDaprItSuite). Plus DaprItFixture,
    │   │                             # ServiceHarnessApi, ItPolling, ItNames, and ItServerApp/itUnionApp/GatedWorkflow
    │   │                             # (the served union app — here, not apps/, because it references ItNames).
    │   └── apps/                     # cross-compiling DaprApp fixtures: Shared, OrderServiceApp,
    │                                 # InventoryServiceApp, EchoServiceClient, CounterActorApp/-Shared,
    │                                 # WorkflowApp, TestDurations, TestUpickleCodec
    ├── jvm/                          # [every file: target.platform jvm]
    │   ├── TestCodecs.scala          # shared test JsonCodec instances (Jackson)
    │   ├── TestDaprExtensions.scala  # test-only Dapr.runWithEndpoints(http, grpc) helper
    │   ├── unit/                     # JVM-server tests: SubscriberTest, BindingDispatchTest, JobDispatchTest,
    │   │                             # DaprServerTestBase, JvmCapabilityDerivationTest (+ fixtures),
    │   │                             # JvmModelsTest, JvmServerRouteDerivationTest
    │   ├── unit/                     # ActorServerRoutingTest (DaprAppServer actor HTTP routing, no sidecar)
    │   ├── integration/              # JVM bring-up for the shared IT suites + JVM-only suites. Harnesses:
    │   │                             # DaprTestContainer, JvmItComponents (renders scripts/it/components),
    │   │                             # SharedDaprItSuite (direct-call), ServerDaprItSuite (hosts the shared itUnionApp
    │   │                             # via Dapr.serve on a vthread — server-delivery), RedisFixture, ServiceHarness,
    │   │                             # JvmItPolling, TestDaprApp. JVM-only suites: Jobs/Conversation
    │   │                             # CapabilityServerTest (no JS SDK twin)
    │   └── apps/                     # OrderServiceMain / InventoryServiceMain @main entry points
    └── js/                           # [every file: target.platform scala-js]
        ├── TestCodecsJs.scala        # same given names over ujson, so shared tests cross-run
        └── integration/              # Wasm+JSPI bring-up for the SAME shared IT suites, against a live sidecar:
                                      # DaprJsItFixtures (SharedDaprItSuite + ServerDaprItSuite testcontainers bring-up)
                                      # + JsItComponents/JsItFacades + JsItPolling + JsItEnv
```

### Integration-test coverage parity

Every capability the JS SDK supports is integration-tested on **both** platforms against a live `daprd`, and the two
platforms share as much as is reasonable — see [JVM-JS-PARITY.md](JVM-JS-PARITY.md) for the full design.

- **One component set, redis everywhere.** `scripts/it/components/*.yaml` + `scripts/it/secrets.json` are the single
  source of truth; the only environment-specific value is `redisHost`, substituted to `redis:6379` (the redis
  container's network alias) on both platforms. Each platform renders the manifests IN-CODE and feeds them to its
  testcontainers `DaprContainer` — `JvmItComponents` + `withComponent(Path)` on the JVM, `JsItComponents` +
  `withComponentFromPath` on JS (the per-platform `SharedDaprItSuite`/`ServerDaprItSuite` stand up the redis the
  manifests point at). `scripts/it/render-components.sh` remains as a standalone renderer for manual use. Both
  platforms therefore run state/pubsub/lock/configuration on `redis`, secrets on `local.file`, crypto on
  `localstorage` — identical backends, no `state.in-memory` divergence, no `scripts/jvm-it/` twin.
- **One suite, shared body; only the fixture is per-platform.** Each capability's calls + assertions AND their munit
  registrations live once as a single cross-platform suite class in `test/shared/integration` (`extends FunSuite,
  <Fixture>`). The only platform-specific part is the mixed-in fixture — `SharedDaprItSuite` (direct-call) or
  `ServerDaprItSuite` (server-delivery) — defined once per platform under the same name, owning bring-up and the
  sync/`Future` boundary (`withDapr` returns `Unit` on the JVM, `Future[Unit]` on JS; munit accepts both). So the
  bodies are literally shared, not merely "equivalent".
- **Server-delivery suites are shared too; only the fixture differs.** Actor, pub/sub delivery, invoke and workflow
  live as single cross-platform suites (`Actor/PubSub/Invoke/WorkflowItTest`) that mix `ServerDaprItSuite`, a fixture
  defined once per platform under the same name. Both implementations host the SAME shared union app
  (`itUnionApp` = `ItServerApp ++ CounterActorApp ++ WorkflowApp ++ GatedWorkflow`) reachable from the sidecar via
  `host.testcontainers.internal`, started sidecar-first (the workflow runtime needs the gRPC endpoint at server-start)
  with daprd app-health-checks pointed at `/dapr/config` so the channel — hence subscriptions and actor types — is
  established once the app is up. The only platform difference is how the server is hosted: `Dapr(config).serve` on a
  virtual thread on the JVM, in-process `Dapr(cfg).serveAsync` on JS (since `serve` suspends forever with no clean
  stop, JS shares ONE sidecar + server across these suites). The test bodies call the *client* capabilities only and
  are byte-identical across platforms. The few genuinely platform-specific checks — the JVM `DaprAppServer` HTTP
  routing/status codes — live in the JVM unit `ActorServerRoutingTest`.

The only capabilities not tested on Scala.js are **jobs** and **conversation**: the JS SDK has no such APIs, so they are
*compile-time absent* on that platform (`DaprCapabilityPlatform`, see the platform-trait section) — not untested.
**Bindings** is the one shared capability with no live-sidecar suite on either platform (covered by derivation + unit
tests on both); that gap is symmetric by design.

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Capability root | `DaprCapability` returns one universal capability per family (`StateCapability`, …), narrowed by name with `instance(name)` → `X.Instance` and by authority by upcasting to `X.ReadOnly` / `X.Instance.ReadOnly` | Single entry point; child capabilities capture scope, preventing escape; the universal capability is a passable "any store / app / job" handle; read-only narrowing cannot be re-widened by `instance` |
| JSON library | upickle | Pure Scala, Scala CLI friendly, automatic derivation |
| Async model | JVM: blocking (`Mono.toFuture().get()`) on virtual threads; JS: JSPI suspension via orphan `js.await` on the Wasm backend | Direct-style API on both platforms with no effect-library dependency; VT parking and JSPI stack suspension are architectural analogues (see Scala.js platform section) |
| Error model | Expected outcomes as values; errors as the sealed `dapr4s.DaprException` hierarchy, translated at each SDK wall by one shared table | Consistent with safe mode's exception-permitting stance; composable with `Try`; the same failure is the same type on every platform, and no SDK type leaks |
| SDK visibility | Zero — Java SDK confined to `src/jvm/internal/`, JS SDK (`@dapr/dapr`) confined to `src/js/internal/` | Users see only Scala types; easier to swap SDKs in future |
| Scope safety | Capture checking: capabilities `^{scope}` | Compiler enforces no DAPR resource outlives its `Dapr(config).run` block (via `import language.experimental.captureChecking`, no `-Ycc` needed) |
| Configuration | Typed `DaprConfig` (`SidecarConfig` / `AppServerConfig` / `ActorRuntimeConfig`) | All endpoints/timeouts/TLS explicit and typed — no env-var reads or system-property manipulation in production code; `grpcTlsInsecure` defaults to `false` |
| Capability base type | `scala.caps.ExclusiveCapability` | All capability traits extend `ExclusiveCapability` — the only sealed subtype of `Capability` that prevents sharing. Enables CC separation checking: no capability escapes its scope or is used concurrently. Sub-capabilities return as `^{this}` to bind lifetime to the parent; override methods must explicitly annotate return types to satisfy CC override checks. |

---

## Workflows and Activities

`Workflow` and `WorkflowActivity[I, O]` provide clean Scala abstractions over the Dapr workflow SDK, hiding all Java types.

### Workflow

Extend `Workflow` and implement `run()(using WorkflowContext): Unit`. `WorkflowContext` is injected as a `given` — user code never binds a `ctx` value. The `WorkflowContext` companion object provides forwarder methods (`WorkflowContext.getInput`, `callActivity`, `complete`, etc.) mirroring the `ActorContext` companion pattern:

```scala
class OrderWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val input = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
    val paymentTask = WorkflowContext.callActivity[ProcessPaymentActivity](input)
    val result = paymentTask.await()
    WorkflowContext.complete(result)
```

`callActivity` is parameterised by the concrete activity **class** (`callActivity[ProcessPaymentActivity](input)`), not a `classOf[...]` argument. The `ActivityDef[A]` typeclass (auto-derived for every `WorkflowActivity[I, O]` subclass with a `ClassTag` and the relevant `JsonCodec`s in scope) links the class to its input/output types, so the input/output types are resolved by the compiler.

`Workflow` is a pure Scala abstract class — no Java type in the public API. Internally, `WorkflowBridge(workflow) extends io.dapr.workflows.Workflow` and is used only during sidecar registration via the named-instance overload `registerWorkflow(name, bridge, "", false)`. The bridge constructs `given WorkflowContext = new WorkflowContextImpl(javaCtx)` and calls `w.run`, so `WorkflowContext` never escapes the bridge's stack frame. The bridge is in `dapr4s.internal` and never visible to users.

**Registration naming**: both workflows and class-based activities register under their **simple** class name (`getSimpleName`) — for workflows this is what users pass to `WorkflowCapability.start(WorkflowName(...))`; for activities it is exposed via `ActivityDef.activityName` and resolved by `callActivity[A]`. Names are first-writer-wins (same as routes), so two classes sharing a simple name collide — override `activityName` to disambiguate. Activities reified by `dapr4s.workflow.WorkflowActivities` are anonymous, so they instead register under a stable `<implClass>#<method>` name computed by the macro. See `DaprAppServer`'s registration loop and `Workflows.scala`.

Workflow operations honour the configured gRPC endpoint: `Dapr` derives a `workflowProperties: Properties` from `config.sidecar` and threads it through to the `DaprWorkflowClient` and `WorkflowRuntimeBuilder`, whose no-arg constructors would otherwise hardcode `localhost:50001`.

`getStatus` and `waitForCompletion` (on `WorkflowCapability.ReadOnly` and `WorkflowCapability.Instance.ReadOnly`, so also on the full tiers) return `Option[WorkflowSnapshot]`, yielding `None` for an unknown or purged instance.

### WorkflowActivity[I, O]

Extend `WorkflowActivity[I, O]` (which requires `JsonCodec[I]` and `JsonCodec[O]` in scope) and implement `execute(input: I)(using DaprCapability): O`. The `DaprCapability` is supplied fresh by the workflow runtime on **every call** — it is a per-call parameter, never captured in a field. Because nothing is captured, activity implementations stay capture-checked ("safe mode") with no `@scala.caps.assumeSafe` annotation:

```scala
class ProcessPaymentActivity extends WorkflowActivity[OrderRequest, PaymentResult]:
  def execute(input: OrderRequest)(using DaprCapability): PaymentResult =
    DaprCapability.invoke:
      InvokeCapability.invoke(PaymentService, InvokeMethodName("charge"), input)[PaymentResult]
```

`WorkflowActivity[I, O]` is a pure Scala abstract class. Internally, `WorkflowActivityBridge[I, O](activity) extends io.dapr.workflows.WorkflowActivity` wraps it for registration via `registerActivity(name, bridge)`. The bridge accesses `activity.inputCodec` / `activity.outputCodec` which are `private[dapr4s]` on the abstract class.

### Task[O]

`WorkflowContext.callActivity(...)`, `WorkflowContext.createTimer(...)`, and `WorkflowContext.waitForExternalEvent(...)` all return `Task[O]`. Call `.await()` to block and get the result, or `.map(f)` to transform it without scheduling new durable work. This is replay-safe inside the workflow runtime.

---

## Actors (Server-side Hosting)

Dapr virtual actors are hosted server-side via `ActorDefinition` without extending any Java class.

### ActorContext

`ActorContext` is a capability trait provided on every actor invocation. It bundles per-instance state access with reminder/timer scheduling:

```scala
@scala.caps.assumeSafe trait ActorContext extends scala.caps.ExclusiveCapability:
  // State
  def get[T: JsonCodec](key: ActorStateKey): Option[T]
  def set[T: JsonCodec](key: ActorStateKey, value: T): Unit
  def remove(key: ActorStateKey): Unit
  // Reminders (persistent — survive actor deactivation)
  def registerReminder[T: JsonCodec](name: ReminderName, data: T, dueTime: Duration, period: Option[Duration] = None): Unit
  def unregisterReminder(name: ReminderName): Unit
  // Timers (non-persistent — lost on deactivation)
  def registerTimer[T: JsonCodec](name: TimerName, data: T, dueTime: Duration, period: Option[Duration] = None): Unit
  def unregisterTimer(name: TimerName): Unit
```

Implemented by `HttpActorContext` which calls the Dapr actor state and reminder/timer HTTP APIs. A companion `object ActorContext` provides static forwarding methods so handlers can call `ActorContext.set(...)` without naming the context parameter.

### ActorDefinition, ActorRoutes, and Route Types

```scala
ActorDefinition(ActorType("Counter")) { id =>
  // `build` is `ActorId => ActorContext ?=> ActorRoutes`, so the per-instance
  // ActorContext is supplied as a `given` — no `given ActorContext = ctx` needed.
  val actor = new CounterActor   // plain Scala class, no special supertype
  ActorRoutes(
    methods = List(
      ActorMethodRoute[IncrReq, Int](ActorMethodName("increment"))(actor.increment),
      ActorMethodRoute[Unit, Int](ActorMethodName("get"))(actor.get),
    ),
    reminders = List(
      ActorReminderRoute[String](ReminderName("reset-reminder"))(actor.onReminder),
    ),
    timers = List(
      ActorTimerRoute[IncrReq](TimerName("auto-tick"))(actor.onTimer),
    ),
  )
}
```

`build` has type `ActorId => ActorContext ?=> ActorRoutes` and is called on every incoming invocation. The fresh `ActorContext` scoped to that `(actorType, actorId)` pair is supplied as a context-function `given`, so handler methods declared `(using ActorContext)` resolve directly without any `given ActorContext = ctx` line. `build` returns an `ActorRoutes` value grouping all three route types.

- `ActorMethodRoute[Req, Resp]` — handles `POST /actors/{type}/{id}/method/{name}`
- `ActorReminderRoute[Payload]` — handles `PUT /actors/{type}/{id}/method/remind/{name}`
- `ActorTimerRoute[Payload]` — handles `PUT /actors/{type}/{id}/method/timer/{name}`

### HTTP Routes (DaprAppServer)

`DaprAppServer` handles:
- `GET /dapr/config` → returns `{"entities": [...registered actor types...], ...}` 
- `POST /actors/{type}/{id}/method/{name}` → dispatches to the matching `ActorMethodRoute`
- `PUT /actors/{type}/{id}/method/remind/{name}` → dispatches to the matching `ActorReminderRoute`; body `{"data":"base64","dueTime":"..."}` — `data` is base64(JSON)
- `PUT /actors/{type}/{id}/method/timer/{name}` → dispatches to the matching `ActorTimerRoute`; same body format
- `DELETE /actors/{type}/{id}` → actor deactivation (returns 200, no local cleanup needed)

Actor state persists via `/v1.0/actors/{type}/{id}/state`. Reminders are registered at `/v1.0/actors/{type}/{id}/reminders/{name}` and timers at `/v1.0/actors/{type}/{id}/timers/{name}`. All sidecar callbacks target `config.sidecar.httpEndpoint` (default `http://localhost:3500`), passed into `HttpActorContext` — there is no `DAPR_HTTP_PORT` environment read. The actor runtime settings reported via `GET /dapr/config` come from `config.actors` (`ActorRuntimeConfig`).

---

## Scala.js platform

dapr4s cross-compiles to Scala.js with a **byte-identical public API** — except for `jobs`/`conversation`, which exist only on the JVM (see the platform-trait technique below) — backed by the Dapr JS SDK (`@dapr/dapr`).

### Identical public API — why

The capability traits, `DaprApp`, the opaque types, and the derivation layer are shared sources (except the JVM-only jobs/conversation surface — see the platform-trait technique). An async-on-JS API fork was rejected for two reasons:

1. The derivation layer generates **synchronous** calls; forking the API would fork every derive engine.
2. The project's documented constraint (see Non-Goals): no async/`Future`-based API — the library is direct-style by design.

So on JS the same synchronous signatures are preserved, and the asynchrony is absorbed below the public API by Wasm + JSPI.

### Wasm + JSPI: the virtual-thread analogue

The Dapr JS SDK is Promise-based. Every JS capability implementation funnels its asynchronous boundary through one helper, `dapr4s.internal.JsAwait.await(p: js.Promise[A]): A` — an **orphan `js.await`** (enabled by the `scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait` import, which appears in that one file only). On the experimental WebAssembly backend, JavaScript Promise Integration (JSPI) **suspends the entire Wasm stack** at this point and returns control to the event loop — no thread is blocked (there are none), inbound work keeps being served, and the stack resumes when the promise settles. This is the exact architectural analogue of a virtual thread parking in `CompletableFuture.get()` on the JVM (`MonoOps.awaitResult`).

Consequences for JS consumers (documented on `src/js/Dapr.scala`):

- Link with the **experimental WebAssembly backend**: `//> using jsEmitWasm true`, `//> using jsModuleKind es`, `//> using jsEsVersionStr es2017`.
- Run on **Node 25+** (JSPI on by default), or Node 23/24 with `--experimental-wasm-jspi`.
- `npm install @dapr/dapr` so the SDK resolves from the directory Node executes in.
- Enter `js.async { ... }` **once at the program edge**: `def main = { js.async { Dapr().run { ... } }; () }`. The JS-only conveniences `Dapr#runAsync` / `Dapr#serveAsync` (returning `js.Promise`) wrap this for callers that prefer it.

**Plain-JS backend: link-time failure by design.** Orphan awaits only link when targeting WebAssembly. The published `_sjs1_3` artifact contains backend-neutral `.sjsir`, so the pure parts of dapr4s (models, codecs, derivation, validation) link fine on the plain JS backend; any code path reaching `Dapr.run`/a capability impl fails **at link time** rather than at runtime. One verified caveat: at least under scala-cli, the plain-JS linker can **wedge** (hang without an error) instead of reporting cleanly when *test* sources contain orphan awaits — which is why the integration suites live in their own sbt project (`integrationJS`, Wasm-linked) and are never linked by the plain-JS unit leg (see the Scala.js test architecture below).

### The per-callback `js.async` re-entry rule

JSPI suspension requires a dynamically enclosing `js.async` on the call stack **with no JavaScript frame in between** (otherwise the engine throws `WebAssembly.SuspendError`). Any Scala lambda invoked *by* a JS API — an express route handler, an SDK activity executor callback, a `Promise.then` reaction — sits below a JS frame. The rule therefore is: **every inbound dispatch re-enters `js.async` per request/invocation** before touching dapr4s code. Each request gets its own suspension scope — like one virtual thread per request on the JVM (`DaprAppServer`'s virtual-thread-per-request executor).

### Capability support matrix and per-protocol mapping

The JS SDK cannot serve all building blocks over one protocol (`configuration`/`crypto` throw `HTTPNotSupportedError` over HTTP), so `Dapr.run` owns up to three clients — an HTTP-protocol `DaprClient` (always created) plus a gRPC `DaprClient` and a `DaprWorkflowClient` created lazily on first use (`LazyClientRef`, the JS twin of the JVM's `AtomicReference` pattern) and closed in `run`'s `finally` block:

| Capability | JS backing |
|---|---|
| state, publish, bindings (outbound), secrets, lock | HTTP-protocol `DaprClient` sub-clients |
| invoke (`invoke` + `invokeHttp`) | **raw sidecar HTTP via Node-global `fetch`** (`/v1.0/invoke`, the same API the JVM calls through `java.net.http`) |
| configuration (get + subscribe), crypto | lazy **gRPC** `DaprClient` (gRPC-only in the JS SDK) |
| actor (client) + `ActorContext` | **raw sidecar HTTP via Node-global `fetch`** (see below) |
| workflow (client) | lazy `DaprWorkflowClient` (gRPC, vendored durabletask) |
| jobs, conversation | **absent at compile time** — the JS SDK has no jobs or conversation API, so these methods do not exist on the JS platform (see the platform-trait technique below); use the JVM platform |
| `serve()`: subscriptions, invoke routes, input bindings, job routes, actor hosting, workflow hosting | express-based `DaprAppServer` twin + `WorkflowHost` — full app-channel parity with the JVM |

**Why raw `fetch` for actors**: the SDK's low-level `ActorClientHTTP` is exactly what is needed but is not exported from the package root (and `@dapr/dapr` has no `exports` map, so deep-requiring it is unsupported). The exported `ActorProxyBuilder` derives the actor type string from the JS class `.name` (mangled/minified under Scala.js) and returns a JS `Proxy` that turns every property access into an invocation — hostile to a typed facade. So `ActorCapabilityImpl`/`HttpActorContext` speak the sidecar's actor HTTP API directly over `fetch` + `JsAwait`, the same SDK-bypass precedent as the JVM's `HttpActorContext`.

`SidecarConfig` mapping: `httpEndpoint` → the HTTP client, `grpcEndpoint` → the gRPC and workflow clients, `apiToken` → `daprApiToken`, `grpcMaxInboundMessageSizeBytes` → the SDK's `maxBodySizeMb`. Everything else (OkHttp pool settings, gRPC-Java keepalive, `maxRetries`, `timeout`, TLS material paths) is JVM-transport-specific and ignored on JS (TLS on/off still follows the endpoint URI scheme).

### Platform-diverging surface: the platform-trait technique

When a building block exists in only one platform SDK (`jobs` and `conversation` exist in the Java SDK but not in `@dapr/dapr`), dapr4s does **not** throw `UnsupportedOperationException` on the other platform — the methods simply do not exist there at compile time. The mechanism is an inherited **platform parent trait** pair:

```scala
// src/shared/DaprCapability.scala (cross-compiled)
trait DaprCapability extends scala.caps.ExclusiveCapability, DaprCapabilityPlatform: ...
object DaprCapability extends DaprCapabilityCompanionPlatform: ...

// src/jvm/DaprCapabilityPlatform.scala — contributes the JVM-only surface
trait DaprCapabilityPlatform:
  this: DaprCapability =>                                   // so ^{this} tracks the same capability
  def jobs: JobsCapability^{this}
  def conversation: ConversationCapability^{this}
trait DaprCapabilityCompanionPlatform:
  def jobs[T](body: JobsCapability ?=> T)(using cap: DaprCapability): T = ...
  def conversation(componentName: ConversationComponentName)[T](...): T = ...

// src/js/DaprCapabilityPlatform.scala — both traits deliberately empty
trait DaprCapabilityPlatform
trait DaprCapabilityCompanionPlatform
```

**Why inherited traits rather than a platform-split `DaprCapability` file**: a Scala companion object must sit in the **same file** as its trait, and `DaprCapability`'s companion carries the transformer API — so the trait and companion cannot themselves be forked per platform without duplicating the whole shared surface. Parent traits let the shared file own everything cross-platform while each platform contributes (or withholds) its extra members. The same pattern repeats wherever the surface diverges: `JobsCapability` (with its `ReadOnly`/`Instance` tiers) and `ConversationCapability` (with its `Instance`) and their models live under `src/jvm/jobs/` and `src/jvm/conversation/` outright (same `dapr4s.jobs`/`dapr4s.conversation` packages as their cross-platform names — and, for jobs, the job routes — in `src/shared/jobs/`/`src/shared/conversation/`), the JVM-only `Jobs.derive` engine lives in `src/jvm/jobs/Jobs.scala`, and `dapr4s.derivation.Forwarders extends ForwardersPlatform` (the JVM twin carries the jobs runtime forwarders the generated code calls; the JS twin is empty — `Forwarders.jobRoute` stays shared because the inbound job-trigger side is cross-platform).

The result: using `DaprCapability.jobs` from Scala.js code is a **compile error** ("value jobs is not a member"), not a runtime surprise, and the published `_sjs1_3` artifact contains no jobs/conversation API at all.

### The express-based `DaprAppServer` twin

The JVM deliberately bypasses the Java SDK's server and hand-rolls the Dapr app-channel protocol on `com.sun.net.httpserver`. The JS twin mirrors that decision on **express 4** (a dependency of `@dapr/dapr`, so always installed): the JS SDK's `DaprServer` is unsuitable for the same reasons its Java counterpart was — its pub/sub callbacks strip the CloudEvent envelope (dapr4s hands the full envelope to subscription handlers) and its invocation listener constrains HTTP verbs (dapr4s accepts every verb and reports it in `InvokeRequest`). The twin is identical route-for-route and status-code-for-status-code: `/dapr/subscribe`, `/dapr/config`, pub/sub routes, input bindings, invocations, `/job/<name>`, and the actor protocol routes. Every handler immediately enters a fresh `js.async` (the re-entry rule above). Express-forced differences (registration via `app.all` to preserve verb-agnostic dispatch, exact instead of prefix matching of `/dapr/*` paths, `path-to-regexp` pattern characters in user route strings, Node's default backlog instead of the `httpBacklog == 0` OS-default sentinel) are documented on the class.

"Blocking forever" (`serve`'s `Nothing` contract) is an orphan await on a never-resolving promise — the JS analogue of `Thread.currentThread().join()`; the express server keeps the event loop alive. SIGINT/SIGTERM stop the listener, drain in-flight requests, close the workflow host, and exit after at most `shutdownGrace`.

### Workflow hosting: the AsyncGenerator coroutine bridge

The JS SDK's orchestration executor drives an **async generator** that yields the SDK's own `Task` objects (`await generator.next(prevResult)` per history event). Scala.js cannot write `async function*`, so `WorkflowCoroutine` hand-implements the AsyncGenerator protocol as a non-native `js.Object` class (`next`/`throw`/`return` + `Symbol.asyncIterator`):

- The dapr4s `Workflow.run` body executes inside its own `js.async` fiber. `Task.await()` = resolve the pending generator *step* promise with `{value: sdkTask, done: false}` (handing over the SDK's own Task instance — the executor `instanceof`-checks it), then orphan-await a fresh *resume* promise. The executor's next `next(v)` / `throw(e)` settles the resume promise, resuming (or failing) the fiber.
- **Strict-alternation safety argument**: the executor awaits every `next()`/`throw()` before processing the next history event, so the generator side and the fiber strictly alternate — at any instant at most one of the two is runnable. Combined with JavaScript's single-threaded execution (JSPI resumes a suspended stack as a promise reaction, never concurrently), each resolver field is written in one phase and consumed-and-cleared in the other; the plain `var`s need no synchronization, and the invariant-breach branches throw loudly if a future SDK version ever drives the generator differently. `generator.return()` is rejected loudly — the vendored executor never calls it.
- **Replay**: each work item re-executes the orchestrator from scratch; when history runs out at an incomplete task, the executor stops driving the generator and the fiber stays suspended on a resume promise nobody will resolve — the whole coroutine graph becomes garbage (abandoned JSPI stacks are collectable by design). This is the JS analogue of the JVM's `OrchestratorBlockedException` unwind.
- **Deterministic `newUuid`**: the JS SDK exposes no deterministic UUID, so `WorkflowContextImpl` mirrors the Java SDK's algorithm (RFC 4122 name-based v5/SHA-1 over `"<instanceId>-<currentUtcDateTime>-<counter>"` in the Java SDK's fixed namespace `9e952958-5e33-4daf-827f-2fa12937b875`) via `node:crypto`. Replay-stable per instance; cross-platform UUID equality is a non-goal (an instance always replays on the platform hosting it).

Registration uses `registerWorkflowWithName`/`registerActivityWithName` with the same simple-class-name rule as the JVM (never `fn.name`, which is mangled under Scala.js). Activities run inside their own per-invocation `js.async`, with the same capability-erasure contract as the JVM `WorkflowActivityBridge`.

### ScalablyTyped-generated facades

The JS interop layer's facades over `@dapr/dapr`, express and the Node stdlib are **generated, not hand-written**. The `stFacades` sbt project enables `ScalablyTypedConverterGenSourcePlugin` (ScalablyTyped converter `1.0.0-beta45`; `stOutputPackage := "dapr4styped"`, `stStdlib := List("es2022")`, Scala 3.3.6) and converts the npm packages of `package.json` `dependencies` (`@dapr/dapr` 3.18.0, the conversion roots `@types/express` and `@types/node`, plus the test-only `testcontainers` and `@dapr/testcontainer-node`) into Scala sources in `target/`, then compiles them. The converter reads the repo's own `node_modules`, installed by `npm ci` from the committed `package-lock.json` (the build runs `npm ci` when needed and overrides scalajs-bundler's `npmUpdate`, which the beta45 plugin requires, so no unlocked install ever happens). `stFacades` compiles without dapr4s's compiler flags (generated facade code would fail capture checking, explicit nulls and fatal warnings). Generated code is **never committed** and never published as a standalone artifact — its `.sjsir` are embedded into the dapr4s jar (below). (Until 2026-10 the scala-cli build ran the converter CLI from `scripts/generate-st-facades.sh` into `~/.ivy2/local`, pinned the digest-versioned jars as `compileOnly.dep`, and embedded them with `scripts/embed-st-facades.sh`; the sbt build replaced all three.)

**Why `stOutputPackage := "dapr4styped"` instead of ST's default `typings`**: the facade classes ship inside the published dapr4s jar, and a consumer running its own ScalablyTyped generation always gets `typings.*` (including its own `typings.std`/`typings.node`) — a default-named embedded tree would collide with it at link time. The rename keeps the embedded tree in a dapr4s-owned namespace. It must be a single identifier (the converter parses it as one `Name`; a dotted value would be backtick-escaped into one bizarre identifier, not a nested package).

**Reproducibility**: the facades are a function of (`package-lock.json`, converter version, converter settings). All three are committed (`package-lock.json`, `project/plugins.sbt`, `build.sbt`), so every machine generates the same sources; CI caches `.st-facades/target` and `~/.cache/scalablytyped` keyed on exactly those files.

**The one hand-written exception**: `src/js/internal/facade/ExpressModule.scala`. ST's entry point for calling the express module captures the module as a namespace import, which under Node ES modules is never callable (`express()` throws `TypeError`), and `express.text` lost its type to a converter warning — so a small `JSImport.Default` shim provides those two members, typed against the ST-generated `Express`/`Handler` types. Everything else uses `dapr4styped.*` directly.

**Consumer story**: the published `dapr4s_sjs1_3` artifact is **self-contained** — consumers resolve it from Maven Central like any ordinary dependency and never run the converter. Two mechanisms in `build.sbt` make that work: (1) `coreJS` `dependsOn(stFacades)` but drops it from `projectDependencies`, so the never-published facades project does not appear in the POM while its classes stay on every build classpath; (2) `embeddedFacades` adds the facade `.sjsir` — only `.sjsir`, not `.class`/`.tasty` — to `coreJS / Compile / packageBin / mappings`. (Consumers only ever *link* against the facades, never compile against them, so `.sjsir` suffices; embedding `.tasty` once made scaladoc document the entire node/express/@dapr typings, blowing the `-javadoc.jar` to ~1.1 GB and over Central's per-file upload limit — the v0.20.0 Scala.js publish failed with HTTP 400 for exactly this reason.) Only the packages of the MAIN roots' transitive closure are embedded (`mainFacadePackages`); the testcontainers closure (`testOnlyFacadePackages`) is test-only. Every generated `dapr4styped.<pkg>` must be in exactly one list or the build fails, so a dependency change cannot silently drop a needed package from the jar. The two Maven-Central libraries the generated code itself links against — `com.olvind::scalablytyped-runtime` and `org.scala-js::scalajs-dom` — are regular `coreJS` dependencies, so they are in the POM.

### Build pattern: platform-scoped dependencies

Plain sbt scoping: the Dapr Java SDK is a `coreJVM` dependency, testcontainers are `integrationJVM` test dependencies, scala-java-time / scalablytyped-runtime / scalajs-dom are `coreJS` dependencies, munit/upickle are shared test dependencies (`%%%`). The `_sjs1_3` POM therefore carries no JVM-only artifacts, and test-scope dependencies are stripped from both POMs (`pomPostProcess`), which keeps them identical to the scala-cli era POMs. (scala-cli achieved the same with `//> using target.platform`-scoped dependency files: `jvm-deps.scala`, `jvm-test-deps.test.scala`, `js-deps.scala`.)

### The Scala.js test architecture

Scala.js tests run as **two legs**, mirroring the JVM split:

- **Unit leg** (plain JS backend, no Docker): `sbt coreJS/test`. The shared unit suites cross-run unchanged (with `test/js/TestCodecsJs.scala` supplying the codec givens over ujson). The integration suites contain orphan `js.await`, and the plain-JS linker does not fail on orphan-await test sources — it **wedges** (hangs without error), so they must not even be linked on this leg: they live in the separate `integrationJS` project.
- **Integration leg** (Wasm + JSPI, real sidecar): `sbt integrationJS/test`. The nine cross-platform suites — state, pub/sub, invoke, secrets, configuration, lock, actors, workflows, crypto, plus Order/Inventory/EndToEnd — live in `test/shared/integration/` and run on the WebAssembly backend against a live `daprd` 1.17 + Redis-backed components + placement and scheduler (workflows require the scheduler in 1.17). The sidecar is started from INSIDE the test runtime by `@dapr/testcontainer-node` (the twin of the JVM `testcontainers-dapr` leg), driven by the JS implementations of the per-platform fixtures in `test/js/integration/`; there is no external bring-up script any more (testcontainers and its Ryuk reaper own the containers). Direct-call suites run per-suite (`SharedDaprItSuite`, each suite's stack torn down as the next starts — `afterAll` can't await on JS); the four server-delivery suites (actor/pub-sub/invoke/workflow) share ONE sidecar + ONE in-process `serveAsync` union server (`ServerDaprItSuite` + `itUnionApp`), reached via `host.testcontainers.internal` with daprd app health checks pointed at `/dapr/config`, because `serve` suspends forever with no clean stop. The suites exercise the *client* capabilities through `Dapr().run`; the union server exercises subscriptions, invoke routes, actor hosting and workflow hosting end to end.

Harness specifics (`integrationJS` settings in `build.sbt`):

- **Linker config**: `ModuleKind.ESModule`, `ESVersion.ES2022`, `withUseWebAssembly(true)`, `withWasmFeatures(_.withUseJSPI(true))`. Scala.js 1.22 requires the explicit JSPI switch for `js.async`/`js.await` on Wasm ("Uses an async block without JSPI support in WebAssembly" otherwise). scala-cli exposed none of this, which is why the scala-cli build pinned 1.14.0 (Scala.js 1.21, where JSPI was implicit) and needed a tmpfs wrapper (its Wasm test runner picked the entry module by directory-listing order) and an exit-code wrapper (it always exited 1 after a green Wasm run). All three are gone with sbt.
- **Node >= 25 on PATH** — Wasm 3.0 and JSPI are on by default there.
- **Sequential suites** (`Test / parallelExecution := false`): with parallel execution, sbt's Scala.js test adapter starts one Node worker per suite, and every worker binds the same fixed app-server ports (`EADDRINUSE`, then invoke 404s and actor-placement timeouts). One runtime also keeps the shared sidecar + union server shared.
- **ESM resolution hook** (`scripts/js-it/node-resolve-hook.mjs` + `node-resolve-delegate.mjs`, passed as `--import` in the test `NodeJSEnv` args): ScalablyTyped emits deep, extensionless CommonJS submodule imports for the testcontainers facades, which Node's ESM resolver rejects; the hook retries them with `.js`. The env also sets `DAPR4S_REPO_ROOT` (where the suites find `scripts/it/`).
- `java.util.UUID.randomUUID()` does **not link** on Scala.js (it reaches for `java.security.SecureRandom`, absent from the javalib) — test ids use a time+`js.Math.random()` scheme (`JsItEnv.uniqueId`).

### Known platform divergences

| Area | JVM | Scala.js |
|---|---|---|
| `waitForExternalEvent(name, timeout)` on timeout | throws the Java SDK's `io.dapr.durabletask.TaskCanceledException` | throws `java.util.concurrent.TimeoutException` (the JS SDK has no timeout overload; dapr4s races the event against a durable timer, mirroring the Java SDK's internal mechanism) |
| `Task.isCancelled` | reflects the SDK task state | always `false` — the vendored JS task model has no cancellation state |
| TLS material (`grpcTlsCertPath`/`KeyPath`/`CaPath`, `grpcTlsInsecure`) | honoured | ignored (JVM-only); TLS on/off follows the endpoint URI scheme |
| `SidecarConfig` transport knobs (OkHttp pool, gRPC-Java keepalive, `maxRetries`, `timeout`) | honoured | ignored — the JS SDK exposes no equivalents; `grpcMaxInboundMessageSizeBytes` maps to `maxBodySizeMb` |
| `jobs`, `conversation` | supported | **absent at compile time** — the methods exist only on the JVM platform trait (see the platform-trait technique above); using them on JS is a compile error |
| Duplicate workflow/activity registration name | silently keeps the first registration | the JS SDK registry throws at registration time (a loud failure for what is a bug either way) |
| `try`/`finally` around a never-completing `Task.await()` | finalizer runs on every replay (the `OrchestratorBlockedException` unwind passes through it) | finalizer does not run (the fiber is abandoned mid-suspension) — out of contract on both platforms anyway: workflow code must be effect-free outside activities |
| `continueAsNew` unwind signal | Java SDK `ContinueAsNewInterruption` (a `RuntimeException` — `NonFatal` would match it; contract: never catch it) | dapr4s's own `ContinueAsNewSignal extends ControlThrowable` — same contract, enforced (a broad `NonFatal` catch cannot swallow it) |
| Shutdown ordering | workflow runtime closed after the HTTP drain completes | runtime stop initiated as soon as the listener stops accepting (a JS signal listener cannot block on the drain) |

---

## Wasm component platform (prototype, opt-in; Central publishing wired but off)

A third platform builds a dapr4s app as a WASI **component** for dapr-wasm-components 0.7.1. It uses the scala-wasm
fork of Scala.js (`ModuleKind.WasmComponent`) in a nested sbt build, `wasm/`, which the root build never loads. The
plan and status are in `docs/WASM-COMPONENTS-PROPOSAL.md` §6. The rules are in AGENTS.md "Wasm component layer rules".

- **Same public API.** The wall depends on dapr4s core: the published `dapr4s_sjs1_3` (its Scala.js IR links into the
  component; the wasm platform traits equal the JS ones, all empty), or `src/shared` compiled for the platform
  (`coreWasm`, `-Ddapr4s.wasm.core=source`) to test shared-code changes. The app
  implements `dapr4s.wasm.DaprComponent` (`def app(using StateCapability): DaprApp`) and names it in the fixed-FQN
  object `dapr4s.wasm.ComponentRoot`. A reactor component has no `main`, so the object is resolved at link time.
- **No connection config and no blocking bridge.** WIT imports are synchronous calls into the composed outbound
  provider, which reads `DAPR_HTTP_PORT` itself.
- **The third wall** (`src/wasm/internal`) is the only place that sees WIT types. It maps WIT `result` variants into
  the same shapes and `DaprException`s as the JVM/JS walls.
- **Two compilers.** Mainline Scala 3 cannot emit WIT imports or exports. Besides the wit-bindgen-scala bindings,
  the Scala 2.13 code is a small hand-written glue in `src/wasm/glue*` (only what the wall uses): plain import/export
  traits with live import objects, and the `@WitExport` trampolines. So the wall is plain Scala 3 against traits,
  and it is unit-tested with fakes.
- **Six artifacts.** `dapr4s-wasm` (adapters), `dapr4s-wasm-glue` (+ `-exports`), `dapr4s-wasm-scalalib-overlay`,
  `dapr4s-wasm-linker` (scala-wasm's linker + our params-via-memory patch) and the `sbt-dapr4s-wasm` plugin, which
  carries every build-level workaround. A consumer: the plugin + one dependency + the fixed-name `ComponentRoot`.
  `publishLocal` works; Central publishing is wired (sbt-ci-release) behind the `PUBLISH_WASM_TO_CENTRAL` switch,
  off until approved. The link-time stubs stay unpublished.

```mermaid
flowchart LR
  subgraph Scala3["Scala 3 nightly + CC flags"]
    App["user app<br/>DaprComponent + ComponentRoot"] --> Wall["wasm wall<br/>WasmStateCapability, InvokeDispatcher"]
    Wall --> Traits["generated traits<br/>StateReadImports / StateImports"]
    Root["AppRoot (wall)"] --> Wall
  end
  subgraph Scala213["Scala 2.13 + scala-wasm plugin (generated)"]
    Live["live import objects<br/>hand-written glue"] --> Lowered["wit-bindgen-scala bindings<br/>dapr4swit"]
    Tramp["@WitExport trampolines"] --> Root
  end
  Traits -.implemented by.-> Live
  Lowered -->|WIT imports| Out["wasi-http outbound provider"] --> Daprd["daprd"]
  Daprd --> In["wasi-http inbound provider"] -->|WIT exports| Tramp
```

## Non-Goals (v1)

- Reactive/async API (Mono/Flux or `Future`s exposed to users) — direct style only. On Scala.js the same direct-style API is achieved via Wasm+JSPI suspension (see the Scala.js platform section) rather than by adding an async API; the JS-only `runAsync`/`serveAsync` conveniences merely wrap the program-edge `js.async`, they do not fork the API.
