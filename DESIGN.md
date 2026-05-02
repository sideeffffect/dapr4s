# scala-safe-dapr — Design

## Goal

A Scala 3 library that exposes every DAPR building block as a **tracked capability**. User code compiles under `import language.experimental.safe` and `import language.experimental.captureChecking`. The DAPR Java SDK is completely hidden — users see only Scala types.

**Requires**: Scala `3.9.0-RC1-bin-20260501-0c8c581-NIGHTLY` (or later nightly). The `import language.experimental.safe` and `import language.experimental.captureChecking` features are fully supported in nightly builds — no `-Ycc` flag is needed.

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
        DS["DaprScope (root capability)"]
        SC["StateCapability^{scope}"]
        PC["PubSubCapability^{scope}"]
        IC["ServiceInvocationCapability^{scope}"]
        SEC["SecretsCapability^{scope}"]
        CC["ConfigurationCapability^{scope}"]
        BC["BindingsCapability^{scope}"]
    end

    subgraph "Internal Layer (@assumeSafe boundaries)"
        DR["DaprRuntime.run { ... }"]
        IMPL["*CapabilityImpl\n(non-safe-mode,\n@assumeSafe methods)"]
        DC["DaprClient\n(Java SDK)"]
    end

    subgraph "DAPR Sidecar"
        SID["localhost:3500 HTTP API\n/ gRPC :50001"]
    end

    UC -->|"summon[DaprScope].state(...)"| DS
    DS --> SC & PC & IC & SEC & CC & BC
    SC & PC & IC & SEC & CC & BC -->|"implemented by"| IMPL
    IMPL -->|"DaprClient.*().block()"| DC
    DC -->|"HTTP/gRPC"| SID

    DR -->|"provides DaprScope ?=>"| UC
```

### Layer 1 — Public API (safe-mode-compatible)

Capability traits, opaque domain types, and the `DaprRuntime.run` entry point. These compile cleanly under both safe mode and capture checking. No Java types are visible.

### Layer 2 — Internal implementations (`@assumeSafe`)

Non-safe-mode Scala that wraps `DaprClient` Java SDK calls. Each class/object is marked `@scala.caps.assumeSafe` (from the `scala.caps` package) so safe-mode user code may call them through the capability trait interfaces. Library authors are responsible for the safety contract; user code cannot add new `@scala.caps.assumeSafe` annotations (the annotation is itself restricted to non-safe-mode code).

Note: safe mode is enabled **per-file** via `import language.experimental.safe` (not globally via a compiler flag). This is intentional: files in `internal/` and `DaprRuntime.scala`/`JsonCodec.scala` must use `@scala.caps.assumeSafe` and therefore cannot have the safe-mode import.

---

## Capability Hierarchy

```mermaid
classDiagram
    class DaprCapability {
        <<sealed trait>>
        Note: any class can be a capability via ^ annotations in nightly CC model
    }
    class DaprScope {
        <<trait>>
        +state(storeName: StoreName) StateCapability^{this}
        +pubsub(pubsubName: PubSubName) PubSubCapability^{this}
        +invoker() ServiceInvocationCapability^{this}
        +secrets(storeName: SecretStoreName) SecretsCapability^{this}
        +config(storeName: ConfigStoreName) ConfigurationCapability^{this}
        +binding(name: BindingName) BindingsCapability^{this}
    }
    class StateCapability {
        <<trait>>
        +get[T](key: String) Option[T]
        +getWithETag[T](key: String) StateEntry[T]
        +save[T](key: String, value: T) Unit
        +saveWithETag[T](key, value, etag: ETag) Unit
        +delete(key: String) Unit
        +transaction(ops: Seq[StateOp]) Unit
    }
    class PubSubCapability {
        <<trait>>
        +publish[T](topic: Topic, data: T) Unit
        +publishWithMetadata[T](topic, data, meta) Unit
    }
    class ServiceInvocationCapability {
        <<trait>>
        +invoke[Req,Resp](appId, method, data) Resp
        +invokeGet[Resp](appId, method) Resp
    }
    class SecretsCapability {
        <<trait>>
        +get(key: String) String
        +getBulk() Map[String,String]
    }
    class ConfigurationCapability {
        <<trait>>
        +get(keys: String*) Map[String,ConfigItem]
    }
    class BindingsCapability {
        <<trait>>
        +invoke[Req,Resp](operation, data) Option[Resp]
    }

    DaprCapability <|-- StateCapability
    DaprCapability <|-- PubSubCapability
    DaprCapability <|-- ServiceInvocationCapability
    DaprCapability <|-- SecretsCapability
    DaprCapability <|-- ConfigurationCapability
    DaprCapability <|-- BindingsCapability
```

> Note: `scala.caps.Capability` is **sealed** in nightly Scala 3 and cannot be extended in user code. In the new CC model, any class/trait can serve as a capability through `^` capture annotations. `DaprScope` and `DaprCapability` do not extend `scala.caps.Capability` — they are tracked via `^{scope}` return type annotations on `DaprScope`'s factory methods.

---

## Opaque Domain Types

All domain identifiers are opaque to prevent accidental misuse (e.g., passing a `PubSubName` where a `StoreName` is expected).

| Type | Wraps | Purpose |
|---|---|---|
| `StoreName` | `String` | DAPR state store component name |
| `PubSubName` | `String` | DAPR pub/sub component name |
| `Topic` | `String` | Pub/sub topic |
| `AppId` | `String` | Target application ID for service invocation |
| `SecretStoreName` | `String` | DAPR secrets store name |
| `ConfigStoreName` | `String` | DAPR configuration store name |
| `BindingName` | `String` | DAPR output binding name |
| `ETag` | `String` | Optimistic concurrency tag |

Smart constructors live in companion objects. Extension methods provide operations that would otherwise require unwrapping.

---

## JSON Serialization

User types must provide a `JsonCodec[T]` given instance. The library ships default instances for primitives and common collections. Users derive instances via upickle's `ReadWriter` derivation or supply custom ones.

```scala
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String): Either[String, T]

object JsonCodec:
  given JsonCodec[String] = ...
  given JsonCodec[Int]    = ...
  given [T: upickle.default.ReadWriter]: JsonCodec[T] = ...
```

---

## Resource Lifecycle (Scope Safety)

`DaprRuntime.run` acquires a `DaprClient`, creates a `DaprScope` that captures the client reference, and releases both on exit. The return type of capabilities created inside `run` captures `DaprScope`, so they cannot outlive the `run` block.

```mermaid
sequenceDiagram
    participant App
    participant DaprRuntime
    participant DaprScope
    participant DaprClient
    participant Sidecar

    App->>DaprRuntime: run { body }
    DaprRuntime->>DaprClient: DaprClientBuilder().build()
    DaprRuntime->>DaprScope: new DaprScopeImpl(client)
    DaprRuntime->>App: body (given DaprScope)
    App->>DaprScope: .state("my-store")
    DaprScope-->>App: StateCapability^{scope}
    App->>DaprScope: state.save("k", value)
    DaprScope->>DaprClient: saveState(...).block()
    DaprClient->>Sidecar: HTTP PUT /v1.0/state/my-store
    Sidecar-->>DaprClient: 200 OK
    DaprClient-->>DaprScope: ()
    DaprScope-->>App: ()
    App->>DaprRuntime: (body complete)
    DaprRuntime->>DaprClient: close()
```

---

## State Machine: StateCapability

```mermaid
stateDiagram-v2
    [*] --> Ready: DaprScope.state(storeName) called

    Ready --> Fetching: get(key)
    Fetching --> Ready: Option[T] returned
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

    note right of Ready: Capability bound to DaprScope;\ncannot escape run{} block
```

---

## Error Handling

All DAPR errors are surfaced as `DaprException` (from the Java SDK, re-exported as a Scala type alias). The library does not catch exceptions internally — callers use `Try` or `Either` adapters if they want explicit error handling. In safe mode, exceptions are explicitly permitted (see [Safe Mode](wiki/scala-capture-checking/safe-mode.md)).

---

## Project Structure (Scala CLI)

```
scala-safe-dapr/
├── project.scala                     # Scala CLI directives (deps, compiler options; nightly Scala)
├── src/
│   ├── Models.scala                  # Opaque types, ETag, StateEntry, ConfigItem, StateOp [safe mode]
│   ├── JsonCodec.scala               # JsonCodec typeclass + default instances [@assumeSafe, non-safe-mode]
│   ├── Capabilities.scala            # All capability traits (DaprCapability subtypes) [safe mode]
│   ├── DaprScope.scala               # DaprScope trait with ^{this} return types [safe mode]
│   ├── DaprRuntime.scala             # DaprRuntime.run entry point [@assumeSafe, non-safe-mode]
│   └── internal/
│       ├── DaprScopeImpl.scala       # DaprScope implementation
│       ├── StateCapabilityImpl.scala
│       ├── PubSubCapabilityImpl.scala
│       ├── InvokerCapabilityImpl.scala
│       ├── SecretsCapabilityImpl.scala
│       ├── ConfigCapabilityImpl.scala
│       └── BindingsCapabilityImpl.scala
└── test/
    ├── unit/
    │   ├── ModelsTest.scala
    │   ├── JsonCodecTest.scala
    │   └── StateCapabilityTest.scala  # mock-based unit tests
    └── integration/
        ├── StateIntegrationTest.scala     # TestContainers
        ├── PubSubIntegrationTest.scala    # TestContainers
        ├── InvokerIntegrationTest.scala   # TestContainers
        └── SecretsIntegrationTest.scala   # TestContainers
```

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Capability root | `DaprScope` provides factory methods | Single entry point; child capabilities capture scope, preventing escape |
| JSON library | upickle | Pure Scala, Scala CLI friendly, automatic derivation |
| Async model | Blocking (`.block()` on `Mono`/`Flux`) | Direct-style compatible; avoids bringing in effect library dependency |
| Error model | Exceptions (Java SDK `DaprException`) | Consistent with safe mode's exception-permitting stance; composable with `Try` |
| Java SDK visibility | Zero — all Java types in `internal/` | Users see only Scala types; easier to swap SDK in future |
| Scope safety | Capture checking: capabilities `^{scope}` | Compiler enforces no DAPR resource outlives its `DaprRuntime.run` block (via `import language.experimental.captureChecking`, no `-Ycc` needed) |

---

## Non-Goals (v1)

- Reactive/async API (Mono/Flux exposed to users) — use blocking for simplicity.
- Actor framework (DaprActor interface) — complex enough to warrant separate treatment.
- Workflow orchestration — complex; requires special determinism constraints.
- Subscription handling (inbound pub/sub) — requires HTTP server integration.
