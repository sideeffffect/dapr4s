# dapr4s — Capability Splitting (Design Exploration)

**Status:** exploration / not yet implemented.
**Scope:** how each DAPR capability trait *could* be subdivided into narrower capabilities, why, and the cost.
**Audience:** anyone evolving the public capability surface in `src/shared/Capabilities.scala`, `src/shared/Actors.scala`, `src/shared/Workflows.scala`, and the JVM-only `JobsCapability` / `ConversationCapability`.

This document follows the question "*`DaprCapability#state` hands out a `StateCapability` bound to one store — what else can be split that way?*" to its conclusion across the whole API.

---

## 1. The two axes of splitting

"Splitting like state" is not one operation — it is **two**, along orthogonal axes. Keeping them apart is what makes the rest of this document tractable.

```mermaid
flowchart TD
    A["I want to hand out a narrower capability than the full one"] --> B{"Narrowing by a NAME/instance<br/>or by METHOD authority?"}
    B -->|"a NAME or instance<br/>(this store, this actor, this app)"| C["NAME / INSTANCE TIER<br/>(Design C)"]
    B -->|"a subset of METHODS<br/>(read-only, encrypt-only)"| D["AUTHORITY TIER"]

    C --> C1["Mechanism: a factory capability<br/>with apply(name) → narrower capability"]
    D --> D1["Mechanism: sub-trait + upcast<br/>powerful trait extends weak trait"]

    C1 --> E{"Will you actually hand out<br/>the narrowed handle?"}
    D1 --> E
    E -->|Yes| F["Worth it"]
    E -->|No| G["Ceremony — skip"]
```

| | **Name / instance tier** | **Authority tier** |
|---|---|---|
| What is removed | a *name argument* (it gets baked into the handle) | a *set of methods* |
| Mechanism | intermediate **factory** capability (`apply(name)`) | **trait subtyping** (`Powerful extends Weak`) — attenuate by upcast |
| New runtime object? | yes (one per name) | **no** — same instance, narrower static type |
| Capture set of result | `^{parent}`, transitively rooted in `dapr` — no lifetime change | identical `^{c}` — no lifetime change |
| Example today | `state(storeName): StateCapability` | none yet |

The single most useful realisation from the research: **authority tiers are nearly free**. If `StateCapability extends ReadState`, then a `StateCapability^{c}` already *is a* `ReadState^{c}`; you attenuate by upcasting — no factory, no extra allocation, same capture set. Only the *name tiers* need the Design-C factory machinery.

---

## 2. The two mechanisms, side by side

### 2a. Authority tier = sub-trait + upcast

```mermaid
classDiagram
    class ReadState {
        <<trait>>
        +get(key) Option~T~
        +getWithETag(key) StateEntry~T~
        +getBulk(keys) Map
        +queryState(query) List
    }
    class StateCapability {
        <<trait>>
        +save(key, value)
        +saveBulk(entries)
        +saveWithETag(...)
        +delete(key)
        +deleteWithETag(...)
        +transaction(ops)
    }
    ReadState <|-- StateCapability : extends
```

`dapr.state(name)` still returns the full `StateCapability`. A query/read handler is declared to take `ReadState^` — and the full capability passes by ordinary subsumption. The read handler *cannot* name `save`/`delete`/`transaction` because its static type has no such members.

### 2b. Name / instance tier = Design-C factory

```mermaid
flowchart LR
    D["DaprCapability"] -->|"state"| AS["AccessStateCapability<br/>(rung 2: any store)"]
    AS -->|"apply(storeName)"| SC["StateCapability<br/>(rung 3: one store)"]
    SC -->|"get / save / ..."| OPS["operations<br/>(no storeName arg)"]

    classDef rung2 fill:#e8f0fe,stroke:#4285f4;
    classDef rung3 fill:#e6f4ea,stroke:#34a853;
    class AS rung2;
    class SC rung3;
```

These compose: a capability can have **both** a name tier (downward) *and* authority tiers (sideways).

### 2c. How attenuation is actually used (and what it does/doesn't guarantee)

```mermaid
sequenceDiagram
    autonumber
    participant H as Service handler (trusted)
    participant D as DaprCapability
    participant C as Helper component (less trusted)

    H->>D: crypto(componentName)
    D-->>H: CryptoCapability^scope
    Note over H: upcast to Encrypt^scope
    H->>C: pass Encrypt only
    Note over C: can seal data…<br/>…cannot decrypt anything
    C-->>H: ciphertext
```

> **Enforcement boundary.** These splits are enforced by the **interface** — the narrow type physically lacks the dangerous method. Capture checking enforces only *region/lifetime* (the handle can't outlive `Dapr.run`). It does **not** enforce the authority narrowing; an `@assumeSafe` cast back to the wide type would defeat it. So this is object-capability attenuation, documented and structurally enforced in normal code, not a CC theorem.

---

## 3. The whole surface at a glance

Every capability and the seam(s) the research found in it:

```mermaid
mindmap
  root(("DaprCapability"))
    State
      name tier: store
      authority: ReadState then StateCapability
    Crypto
      authority: Encrypt and Decrypt
      name tier: key+algorithm
    Secrets
      authority: SecretReader then SecretsCapability
      name tier: per-key
    Workflow
      authority: Observer then Controller, plus Launcher
      instance tier: by instanceId
    ActorContext
      authority: read then write state
      authority: schedule then manage callbacks
    Actor
      instance tier: type then id
    Invoke
      name tier: per app then method
    Jobs
      name tier: per job
      authority: JobReader then JobsCapability
    Configuration
      authority: ConfigReader then ConfigurationCapability
      name tier: per-key
    Publish
      name tier: per topic
    Bindings
      authority split: skip
    Lock
      authority split: skip
    Conversation
      authority split: skip
```

---

## 4. Ranking: payoff vs cost

```mermaid
quadrantChart
    title Capability split — payoff vs implementation cost
    x-axis "Low cost" --> "High cost"
    y-axis "Low payoff" --> "High payoff"
    quadrant-1 "Plan in"
    quadrant-2 "Do first"
    quadrant-3 "Maybe"
    quadrant-4 "Skip"
    "Crypto enc/dec": [0.20, 0.92]
    "Secrets per-key": [0.45, 0.88]
    "Workflow tiers": [0.72, 0.85]
    "Invoke per-app": [0.50, 0.70]
    "ActorContext domains": [0.25, 0.60]
    "Actor type/id": [0.55, 0.50]
    "Config read/subscribe": [0.35, 0.45]
    "Publish per-topic": [0.50, 0.40]
    "Jobs read/write": [0.50, 0.35]
    "Lock resource": [0.45, 0.20]
    "Bindings": [0.40, 0.15]
    "Conversation": [0.20, 0.10]
```

---

## 5. Per-capability designs

### 🥇 5.1 Crypto — encrypt vs decrypt (asymmetric authority)

`encrypt(keyName, plaintext, algorithm)` and `decrypt(ciphertext)` are independent authorities fused into one trait — the sealing/opening analog of sign/verify. A component that only emits sealed data should never be able to open anything.

```mermaid
classDiagram
    class Encrypt {
        <<trait>>
        +encrypt(keyName, plaintext, algorithm) Bytes
        +encryptString(keyName, plaintext, algorithm) Bytes
    }
    class Decrypt {
        <<trait>>
        +decrypt(ciphertext) Bytes
        +decryptString(ciphertext) String
    }
    class CryptoCapability {
        <<trait>>
        +componentName CryptoComponentName
    }
    Encrypt <|-- CryptoCapability : extends
    Decrypt <|-- CryptoCapability : extends
```

- **Authority tier** (sub-trait + upcast): `Encrypt` ⊕ `Decrypt`, with `CryptoCapability extends both`.
- **Bonus name tier:** `encrypt` carries `keyName` + `algorithm`, so a Design-C sub-tier `crypto(component).key(keyName, algorithm)` yields a "seal with exactly this key" handle.
- **Verdict:** highest payoff, lowest cost. Real security meaning, pure subtyping.

### 🥇 5.2 Secrets — get(key) vs getBulk (privilege escalation hiding as a sibling)

`getBulk` reads **every secret in the store**; `get(key)` reads one. Today the same handle grants both.

```mermaid
flowchart LR
    D["DaprCapability"] -->|"secrets(store)"| S["SecretsCapability<br/>(get + getBulk)"]
    S -. "upcast" .-> RO["SecretReader<br/>(get only)"]
    S -->|"key(SecretKey)"| K["OneSecretCapability<br/>(read only 'db-password')"]

    classDef danger fill:#fce8e6,stroke:#ea4335;
    classDef safe fill:#e6f4ea,stroke:#34a853;
    class S danger;
    class K safe;
```

- **Authority tier:** separate `getBulk` (read-all) from per-key `get`.
- **Name tier (stronger):** `secrets(store).key(SecretKey)` → "may read only this one secret". Secrets are the most sensitive surface in the library; this is arguably the single most valuable attenuation here.
- **Verdict:** do it.

### 🥇 5.3 Workflow — launch / observe / control + instance tier (richest target)

Eleven methods cluster cleanly by authority, and ten of them take a `WorkflowInstanceId` (a collapsed instance tier the companion already exposes as `id.suspend()` extensions).

State machine of an instance, annotated by which authority tier drives each edge:

```mermaid
stateDiagram-v2
    [*] --> Running : start  «Launcher»
    Running --> Suspended : suspend  «Controller»
    Suspended --> Running : resume  «Controller»
    Running --> Running : raiseEvent  «Controller»
    Running --> Running : getStatus / waitForCompletion  «Observer»
    Running --> Completed : (logic completes)
    Running --> Terminated : terminate  «Controller»
    Completed --> [*] : purge  «Controller»
    Terminated --> [*] : purge  «Controller»
```

Authority tiers:

```mermaid
classDiagram
    class WorkflowObserver {
        <<trait>>
        +getStatus(id)
        +waitForCompletion(id, timeout)
    }
    class WorkflowController {
        <<trait>>
        +suspend(id)
        +resume(id)
        +terminate(id)
        +purge(id)
        +raiseEvent(id, event, payload)
    }
    class WorkflowLauncher {
        <<trait>>
        +start(name)
        +startWithId(name, id)
    }
    class WorkflowCapability {
        <<trait>>
    }
    WorkflowObserver <|-- WorkflowController
    WorkflowController <|-- WorkflowCapability
    WorkflowLauncher <|-- WorkflowCapability
```

Read-only `WorkflowObserver` is the base; `WorkflowController` **extends** it (you can't control an instance you can't observe), so a "monitor only" grant is `WorkflowObserver` and a "manage existing instances" grant is `WorkflowController`. `WorkflowLauncher` (`start*`) is orthogonal — minting instances is a separate authority — and `WorkflowCapability` extends both leaves.

Instance tier (Design C), folding away the repeated `instanceId` argument:

```mermaid
flowchart LR
    W["WorkflowCapability"] -->|"start(...)"| ID["WorkflowInstanceId"]
    W -->|"instance(id)"| INST["WorkflowInstance<br/>(status/suspend/resume/<br/>terminate/raiseEvent/purge)"]
    ID -.->|"already exposed as<br/>id.suspend() extensions"| INST
```

> **Note — adjust the `WorkflowInstanceId` extension methods.** The companion currently exposes the instance ops as fluent extensions on `WorkflowInstanceId` (`id.suspend()`, `id.getStatus()`, …), each taking a `using WorkflowCapability`. Under this split they should instead require the *narrowest* trait that carries the op — `id.getStatus()` / `id.waitForCompletion()` on `using WorkflowObserver`, and `id.suspend()` / `resume()` / `terminate()` / `purge()` / `raiseEvent()` on `using WorkflowController` — so the extensions don't silently re-demand the full capability and defeat the attenuation. (If the `instance(id)` Design-C tier lands, these extensions largely become methods on `WorkflowInstance` instead.)

- **Verdict:** biggest API win. Read-only observation separates cleanly from control, and isolating destructive `terminate`/`purge` from "kick off a workflow" code is worth it; the instance grouping already half-exists.

### 🥈 5.4 ActorContext — read-state / schedule / cancel

The source partitions the trait by `--- State ---`, `--- Reminders ---`, `--- Timers ---` banners, but those banners are *not* the best authority seams. The reminder-vs-timer banner is a persistence detail (persistent vs non-persistent), not a privilege boundary — and you rarely want "may touch reminders but not timers". The privilege boundaries that matter are **read vs the rest of state**, and **scheduling a callback vs cancelling one** — and the latter cuts *across* both reminders and timers. So the split groups by verb, not by reminder/timer:

```mermaid
classDiagram
    class ReadActorState {
        <<trait>>
        +get(key)
    }
    class WriteActorState {
        <<trait>>
        +set(key, value)
        +remove(key)
    }
    class ScheduleCallbacks {
        <<trait>>
        +registerReminder(...)
        +registerTimer(...)
    }
    class ManageCallbacks {
        <<trait>>
        +unregisterReminder(name)
        +unregisterTimer(name)
    }
    class ActorContext {
        <<trait>>
    }
    ReadActorState <|-- WriteActorState
    ScheduleCallbacks <|-- ManageCallbacks
    WriteActorState <|-- ActorContext
    ManageCallbacks <|-- ActorContext
    note for ScheduleCallbacks "register grouped across reminders AND timers"
    note for ManageCallbacks "adds unregister — also grouped across both"
```

Two linear lineages, each read/light → mutating/heavy: `ReadActorState` → `WriteActorState` (adds `set`/`remove`), and `ScheduleCallbacks` → `ManageCallbacks` (adds `unregister*`). `ActorContext` extends the two leaves. `ManageCallbacks` is the renamed `CancelCallbacks` — since it extends `ScheduleCallbacks` it carries both register and unregister, so "manage" reads better than "cancel".

- **Verdict:** cheap (pure subtyping), medium payoff. `ReadActorState` is the valuable read-only grant; the lineages let you grant "read+write state but no callbacks", or "schedule callbacks but not cancel them", uniformly across reminders and timers.

### 🥈 5.5 Actor — type then id (a collapsed instance tier)

`actor(actorType, actorId)` is a **two-name factory** that flattens a natural Design-C tier.

```mermaid
flowchart LR
    D["DaprCapability"] -->|"actor(actorType)"| AT["ActorTypeCapability<br/>(any Counter)"]
    AT -->|"instance(actorId)"| AC["ActorCapability<br/>(Counter #42)"]
    AC -->|"invoke / invokeVoid"| M["actor methods"]
```

- **Verdict:** mirrors the state design exactly; useful for "this actor type only" grants or code addressing many instances of one type.

### 🥈 5.6 Invoke — per-target attenuation (the inverse of state)

`InvokeCapability` is a single shared handle (Design B — `appId`+`method` per call). Anyone holding it can RPC **any** app.

```mermaid
flowchart LR
    D["DaprCapability"] -->|"invoke"| I["InvokeCapability<br/>(any app, any method)"]
    I -->|"app(AppId)"| A["AppInvokeCapability<br/>(only payment-service)"]
    A -->|"method(name)"| MM["MethodInvokeCapability<br/>(only 'charge')"]

    classDef danger fill:#fce8e6,stroke:#ea4335;
    classDef safe fill:#e6f4ea,stroke:#34a853;
    class I danger;
    class A safe;
```

- **Verdict:** the service-to-service least-privilege story. Invoke *lacks* the tier state *has*; adding a downward `app(...)` tier is the symmetric fix.

### 🥉 5.7 Jobs — currently "Design B", plus read/write

`schedule` / `scheduleOnce` / `get` / `delete` all take a `JobName` per call. Two independent splits — a name tier and the read-only-base authority split:

```mermaid
flowchart TB
    subgraph "Name tier (Design C)"
        J["JobsCapability"] -->|"job(JobName)"| JH["JobHandle<br/>(get/delete, no name arg)"]
    end
    subgraph "Authority tier (subtyping)"
        JR["JobReader<br/>get"] -->|"extended by"| JC["JobsCapability<br/>+ schedule / scheduleOnce / delete"]
    end
```

Per the read-only-base rule, only `JobReader` (`get`) is extracted; the mutating `schedule` / `scheduleOnce` / `delete` stay on `JobsCapability` (no separate `JobScheduler` / `JobAdmin`).

- **Verdict:** modest payoff; the read-only `JobReader` grant is the main draw.

### 🥉 5.8 Configuration — read vs subscribe

`get(keys)` is a one-shot read; `subscribe(keys)(onChange)` opens a **long-lived stream**, returns `AutoCloseable^{this}`, and is the *only* callback capability in the library.

```mermaid
classDiagram
    class ConfigReader {
        <<trait>>
        +get(keys) Map
    }
    class ConfigurationCapability {
        <<trait>>
        +subscribe(keys)(onChange) AutoCloseable
        +storeName ConfigurationStoreName
    }
    ConfigReader <|-- ConfigurationCapability
    note for ConfigurationCapability "subscribe holds a resource (stream + callback) — heavier authority"
```

- **Verdict:** isolates the resource-holding streaming authority from plain reads. Reasonable; add per-key name tier if demand exists.

### 🥉 5.9 Publish — per-topic tier

All three methods are writes (no read/write seam), but `topic` is a routing dimension worth attenuating.

```mermaid
flowchart LR
    D["DaprCapability"] -->|"publish(pubsub)"| P["PublishCapability"]
    P -->|"topic(Topic)"| T["TopicPublishCapability<br/>(only 'orders')"]
```

- **Verdict:** meaningful "publish only to topic X" grant; mirrors state→key.

### 5.10 Weak / skip

```mermaid
flowchart TD
    B["Bindings: invoke vs invokeOneWay is response-shape, not authority;<br/>operation strings are component-specific"] --> X["only the existing bindingName factory is a clean tier"]
    L["Lock: tryLock/unlock are a matched pair you need together;<br/>resourceId is dynamic"] --> Y["low value"]
    C["Conversation: single converse method"] --> Z["nothing to split beyond componentName"]
```

---

## 6. Cross-cutting: activities and actors get the *whole* capability

`WorkflowActivity.execute(input)(using DaprCapability)` and actor `build` lambdas receive the **entire** `DaprCapability`. None of the trait splits above help here — the lever is handing those callbacks a *narrowed* capability (only the sub-capabilities they declare). This is a separate, larger design question and is called out so it isn't mistaken for something the per-trait splits solve.

```mermaid
flowchart LR
    RT["Workflow / Actor runtime"] -->|"today: full DaprCapability"| EX["execute(...) / build(...)"]
    RT -. "future: only the declared sub-capabilities" .-> EX
```

---

## 7. Recommendation & sequencing

```mermaid
flowchart LR
    P1["Phase 1 (cheap, high value)<br/>• Crypto Encrypt/Decrypt<br/>• Secrets SecretReader / no-bulk<br/>• ActorContext read/schedule/cancel"]
    P2["Phase 2 (structural)<br/>• Workflow launch/observe/control<br/>  + instance tier<br/>• Invoke per-app tier"]
    P3["Phase 3 (opportunistic)<br/>• Actor type→id<br/>• Config read/subscribe<br/>• Publish per-topic<br/>• Jobs read/write"]
    P1 --> P2 --> P3
```

**Rules of thumb that fell out of the research:**

1. **Authority tiers first** — they are sub-trait + upcast: no new objects, no capture-set change, no factory plumbing.
2. **Name tiers only where you will hand out the narrowed handle** — otherwise the intermediate capability is pure ceremony (`dapr.state(name)` stays terser than `dapr.state.apply(name)` if nobody ever passes a bare rung-2 handle around). Where you do add one, name the selector `apply` so `dapr.state(name).get(key)` reads unchanged while `dapr.state` becomes the passable rung-2 capability.
3. **Prioritise by what's dangerous to over-grant**, not by symmetry: decrypt, read-all-secrets, terminate/purge, arbitrary-app RPC.

---

## 8. Worked example — "Design C" applied to *every* capability

This section shows the concrete shape of the new capabilities if the name/instance tier were applied uniformly. The rule is mechanical:

> Every `def x(name: N): XCapability` on `DaprCapability` becomes `def x: AccessXCapability` (no argument), and the name moves to `AccessXCapability#apply(name): XCapability`. Where a capability carries the name on its *methods* today (invoke, jobs, workflow instances), that name is lifted out the same way.

So the root shrinks to a set of **argument-less accessors**:

```mermaid
flowchart LR
    D["DaprCapability"]
    D --> AS["AccessStateCapability"]
    D --> AP["AccessPublishCapability"]
    D --> AI["AccessInvokeCapability"]
    D --> ASe["AccessSecretsCapability"]
    D --> AC["AccessConfigurationCapability"]
    D --> AB["AccessBindingsCapability"]
    D --> AL["AccessLockCapability"]
    D --> AA["AccessActorCapability"]
    D --> AW["AccessWorkflowCapability"]
    D --> ACr["AccessCryptoCapability"]
    D --> AJ["AccessJobsCapability (JVM)"]
    D --> ACo["AccessConversationCapability (JVM)"]
```

### 8.1 The uniform pattern

Every accessor is the same two-trait shape. Shown generically, then instantiated for state:

```mermaid
classDiagram
    class AccessXCapability {
        <<trait>>
        +apply(name: N) X
    }
    class XCapability {
        <<trait>>
        +name: N
        +op1(...)
        +op2(...)
    }
    AccessXCapability ..> XCapability : "apply returns (rung 3)"
    note for AccessXCapability "rung 2 — 'any X' — argument-less; held and passed around"
    note for XCapability "rung 3 — 'this X' — methods never mention N"
```

```mermaid
classDiagram
    class AccessStateCapability {
        <<trait>>
        +apply(storeName: StateStoreName) StateCapability
    }
    class StateCapability {
        <<trait>>
        +storeName: StateStoreName
        +get(key, consistency) Option~T~
        +save(key, value)
        +transaction(ops)
        +queryState(query) List
    }
    AccessStateCapability ..> StateCapability
```

**Key point:** for the eight capabilities that already take the name in the factory (state, publish, secrets, configuration, bindings, lock, crypto, conversation), **rung 3 is today's trait, unchanged** — only the rung-2 `Access*` trait is new. `dapr.state(name)` keeps reading identically because `apply` is invoked with the same argument list; the gain is that `dapr.state` is now itself a passable "any store" handle.

### 8.2 The three capabilities whose methods lose an argument

For these, the name lives on the methods today, so uniform Design C changes rung 3 too — every method drops the lifted name.

```mermaid
classDiagram
    class AccessInvokeCapability {
        <<trait>>
        +apply(appId: AppId) InvokeCapability
    }
    class InvokeCapability {
        <<trait>>
        +appId: AppId
        +invoke(method, data, httpMethod, metadata) Resp
        +invoke(method) Resp
    }
    AccessInvokeCapability ..> InvokeCapability
    note for InvokeCapability "appId dropped from every method"
```

```mermaid
classDiagram
    class AccessJobsCapability {
        <<trait>>
        +apply(name: JobName) JobCapability
    }
    class JobCapability {
        <<trait>>
        +jobName: JobName
        +schedule(data, schedule, dueTime, repeats, ttl)
        +scheduleOnce(data, dueTime, ttl)
        +get() Option~JobDetails~
        +delete()
    }
    AccessJobsCapability ..> JobCapability
    note for JobCapability "JobName dropped from every method"
```

Workflow is the interesting one: `start*` *mints* the instance id, so the accessor keeps the launch methods, and `apply(id)` hands back an instance-scoped capability holding the id-less operations.

```mermaid
classDiagram
    class AccessWorkflowCapability {
        <<trait>>
        +start(name) WorkflowInstanceId
        +startWithId(name, id) WorkflowInstanceId
        +apply(id: WorkflowInstanceId) WorkflowInstanceCapability
    }
    class WorkflowInstanceCapability {
        <<trait>>
        +instanceId: WorkflowInstanceId
        +getStatus() Option~WorkflowSnapshot~
        +suspend()
        +resume()
        +terminate()
        +raiseEvent(event, payload)
        +waitForCompletion(timeout) Option~WorkflowSnapshot~
        +purge() Boolean
    }
    AccessWorkflowCapability ..> WorkflowInstanceCapability
```

### 8.3 Actor — two names, two tiers

The actor accessor is the only one that descends **two** rungs (`ActorType` then `ActorId`), each an `apply`:

```mermaid
flowchart LR
    D["DaprCapability"] -->|".actor"| AA["AccessActorCapability"]
    AA -->|"apply(actorType)"| AT["ActorTypeCapability<br/>(val actorType)"]
    AT -->|"apply(actorId)"| AC["ActorCapability<br/>(val actorType, val actorId)<br/>invoke / invokeVoid — no type/id args"]
```

### 8.4 Full mapping

| Capability | rung 2 (new) | `apply` arg(s) | rung 3 | rung-3 methods change? |
|---|---|---|---|---|
| state | `AccessStateCapability` | `StateStoreName` | `StateCapability` | no |
| publish | `AccessPublishCapability` | `PubSubName` | `PublishCapability` | no |
| secrets | `AccessSecretsCapability` | `SecretStoreName` | `SecretsCapability` | no |
| configuration | `AccessConfigurationCapability` | `ConfigurationStoreName` | `ConfigurationCapability` | no |
| bindings | `AccessBindingsCapability` | `BindingName` | `BindingsCapability` | no |
| lock | `AccessLockCapability` | `LockStoreName` | `LockCapability` | no |
| crypto | `AccessCryptoCapability` | `CryptoComponentName` | `CryptoCapability` | no |
| conversation | `AccessConversationCapability` | `ConversationComponentName` | `ConversationCapability` | no |
| invoke | `AccessInvokeCapability` | `AppId` | `InvokeCapability` | **yes** — drop `appId` |
| jobs | `AccessJobsCapability` | `JobName` | `JobCapability` | **yes** — drop `JobName` |
| workflow | `AccessWorkflowCapability` (+`start*`) | `WorkflowInstanceId` | `WorkflowInstanceCapability` | **yes** — drop `instanceId` |
| actor | `AccessActorCapability` | `ActorType` → `ActorId` | `ActorCapability` (via `ActorTypeCapability`) | **yes** — drop type/id |

> Note: `ActorContext` and `WorkflowContext` are framework-provided (not acquired from `DaprCapability`), so the root-level Design C does not reach them. Their internal name args (`ActorStateKey`, `ReminderName`, `EventName`…) could be lifted analogously, but that's a separate, lower-value move.

---

## 9. Worked example — the "authority split" applied to *every* capability

Here the mechanism is **trait subtyping**: define narrow sub-traits, and let the full capability `extend` all of them. No factory, no new runtime object, identical `^{c}` capture set — you attenuate by *upcasting* to a sub-trait. The diagrams below show the new sub-traits each capability would gain.

**Guiding principle — the line is read-only vs everything-else.** The one seam worth drawing in almost every capability is between **read-only** operations and **anything that creates, changes, or deletes**. So the base sub-trait is the read-only view, and the concrete capability extends it with the mutating methods (`ReadState <|-- StateCapability`, `JobReader <|-- JobsCapability`, `WorkflowInfo <|-- WorkflowContext`, …). Two riders on top of that:

1. **A finer read/read split is kept only where one read is far more powerful than another** — `SecretReader.get(key)` vs `SecretsCapability.getBulk` (reads *every* secret), and `ConfigReader.get` vs `ConfigurationCapability.subscribe` (opens a live stream). Both sides are read-only, but the broad read is worth denying on its own, so the narrow reader is extracted.
2. **The mutating side may form a linear lineage** rather than a flat set, when one mutating authority naturally subsumes another — e.g. callback *management* extends callback *scheduling*, and workflow *control* extends workflow *observation*.

**Naming.** The concrete, most-powerful trait keeps its original `*Capability` / `*Context` name (`StateCapability`, `CryptoCapability`, `ActorContext`, …). The *new* narrow sub-traits drop that suffix — `ReadState`, `Encrypt`, `SecretReader`, `ScheduleCallbacks`. The suffix marks "the full thing you acquire"; a sub-trait is an attenuated view, not a thing you acquire directly. A consequence of the read-only-base rule: there is a `ReadState` but no `WriteState` — the write methods simply stay on the concrete `StateCapability` rather than getting an inverse "write-only" trait that nobody can use without reads anyway (see the per-capability notes).

### 9.1 The capabilities with a clean authority axis

```mermaid
classDiagram
    direction LR
    class ReadState {
        <<trait>>
        +get() +getWithETag() +getBulk() +queryState()
    }
    class StateCapability {
        <<trait>>
        +save() +saveBulk() +saveWithETag()
        +delete() +deleteWithETag() +transaction()
    }
    ReadState <|-- StateCapability
    note for ReadState "reads only — no transaction (see below)"
```

**Why no `transaction` on `ReadState`?** You asked whether `ReadState` could carry a read-only `transaction`. It can't be backed by anything: Dapr's transactional API batches **writes only** — a `StateOp` is an upsert or a delete; there is no read operation type to put in a transaction. Consistent multi-key reads are already served by `getBulk`, which is on `ReadState`. So `ReadState` = `get` / `getWithETag` / `getBulk` / `queryState`, and `transaction` lives only on the full `StateCapability`.

**Why no `WriteState`?** Read-only has obvious value; write-only is weaker. Pros and cons of minting a separate `WriteState`:

| | |
|---|---|
| **Pros** | Models a genuine "blind sink" — an append-only audit/event writer that should never read back. Defends against exfiltration (can't read what it can't read). Symmetry. |
| **Cons** | Most writes *need* a prior read: `saveWithETag`/`deleteWithETag` consume an ETag obtained from `get`/`getWithETag`, so optimistic-concurrency flows can't use a write-only handle. `getBulk`/`queryState` are reads too. The only fully write-only path is blind `save`/`delete`/`transaction`, which is a narrow niche. So the trait would rarely be the right grant, and "leftover-of-ReadState" isn't independently compelling. |

**Decision:** keep `ReadState`; **do not** add `WriteState` — the write methods stay on `StateCapability`. (If a real blind-writer consumer ever appears, `WriteState` is a cheap additive sub-trait at that point.)

```mermaid
classDiagram
    direction LR
    class Encrypt {
        <<trait>>
        +encrypt() +encryptString()
    }
    class Decrypt {
        <<trait>>
        +decrypt() +decryptString()
    }
    class CryptoCapability
    Encrypt <|-- CryptoCapability
    Decrypt <|-- CryptoCapability
```

```mermaid
classDiagram
    direction LR
    class SecretReader {
        <<trait>>
        +get(key) Option~SecretValue~
    }
    class SecretsCapability {
        <<trait>>
        +getBulk() Map
    }
    SecretReader <|-- SecretsCapability
    note for SecretsCapability "getBulk reads ALL secrets — the dangerous one; stays here"
```

**No `BulkSecretReader`.** You asked whether `getBulk` can just live on `SecretsCapability` — yes, and it should. `getBulk` (read every secret in the store) is the *high-authority* method, so it belongs on the full capability; the only sub-trait worth extracting is the safe `SecretReader` (single-key `get`). Attenuating to `SecretReader` is exactly how you deny bulk reads. A `BulkSecretReader` trait would be the inverse leftover — no standalone value — so it isn't created. (Same shape as `ReadState`: extract the safe view, leave the powerful method on the concrete.)

```mermaid
classDiagram
    direction LR
    class ConfigReader {
        <<trait>>
        +get(keys) Map
    }
    class ConfigurationCapability {
        <<trait>>
        +subscribe(keys)(onChange) AutoCloseable
    }
    ConfigReader <|-- ConfigurationCapability
    note for ConfigurationCapability "subscribe opens a stream + callback — heavier; stays here"
```

Same shape again: `subscribe` (the heavier, resource-holding, stream-opening method) stays on `ConfigurationCapability`; only the plain `ConfigReader` (`get`) is extracted, since "may read config but not open live subscriptions" is the valuable narrow grant.

```mermaid
classDiagram
    direction LR
    class WorkflowObserver {
        <<trait>>
        +getStatus() +waitForCompletion()
    }
    class WorkflowController {
        <<trait>>
        +suspend() +resume() +terminate() +purge() +raiseEvent()
    }
    class WorkflowLauncher {
        <<trait>>
        +start() +startWithId()
    }
    class WorkflowCapability
    WorkflowObserver <|-- WorkflowController
    WorkflowController <|-- WorkflowCapability
    WorkflowLauncher <|-- WorkflowCapability
    note for WorkflowObserver "read-only"
    note for WorkflowController "control extends observe — destructive terminate/purge"
```

Here the mutating side is itself a lineage: `WorkflowController` extends `WorkflowObserver` (controlling an instance implies being able to observe it). `WorkflowLauncher` (`start*`) is orthogonal — minting new instances is a different authority from managing existing ones — so `WorkflowCapability` extends both `WorkflowController` and `WorkflowLauncher`.

```mermaid
classDiagram
    direction LR
    class JobReader {
        <<trait>>
        +get(name) Option~JobDetails~
    }
    class JobsCapability {
        <<trait>>
        +schedule(...) +scheduleOnce(...) +delete(name)
    }
    JobReader <|-- JobsCapability
```

Read-only `get` is the only extracted view; `schedule` / `scheduleOnce` / `delete` are all mutating and stay on `JobsCapability` (no separate `JobScheduler` / `JobAdmin` — those were write-side leftovers, against the read-only-base rule).

The two context capabilities split the same way, but `ActorContext` has **two independent lineages**, each a read/light → mutating/heavy chain:

- **State lineage:** `ReadActorState` (`get`) → `WriteActorState` (adds `set` / `remove`). The write trait extends the read trait, so "may read+write actor state but not schedule callbacks" is a grantable view.
- **Callback lineage:** `ScheduleCallbacks` (`registerReminder` / `registerTimer`) → `ManageCallbacks` (adds `unregisterReminder` / `unregisterTimer`). Management extends scheduling, so you can grant "schedule only" or "schedule + cancel", but not the odd "cancel only". Note both group by **verb across reminders *and* timers**, not by reminder vs timer (see §5.4).

`ActorContext` then extends the two leaves (`WriteActorState`, `ManageCallbacks`), inheriting everything transitively:

```mermaid
classDiagram
    direction LR
    class ReadActorState {
        <<trait>>
        +get(key)
    }
    class WriteActorState {
        <<trait>>
        +set(key, value) +remove(key)
    }
    class ScheduleCallbacks {
        <<trait>>
        +registerReminder(...) +registerTimer(...)
    }
    class ManageCallbacks {
        <<trait>>
        +unregisterReminder(...) +unregisterTimer(...)
    }
    class ActorContext {
        <<trait>>
    }
    ReadActorState <|-- WriteActorState
    ScheduleCallbacks <|-- ManageCallbacks
    WriteActorState <|-- ActorContext
    ManageCallbacks <|-- ActorContext
```

> `ManageCallbacks` is the renamed `CancelCallbacks` — since it now extends `ScheduleCallbacks` it carries register *and* unregister, so "Manage" fits better than "Cancel".

```mermaid
classDiagram
    direction LR
    class WorkflowInfo {
        <<trait>>
        +instanceId +isReplaying +getInput() +newUuid()
    }
    class WorkflowContext {
        <<trait>>
        +callActivity() +callActivityByName()
        +createTimer() +waitForExternalEvent()
        +complete(output) +continueAsNew(input)
    }
    WorkflowInfo <|-- WorkflowContext
    note for WorkflowInfo "read-only / deterministic-pure: safe to hand to helpers"
```

`WorkflowInfo` is the read-only (and replay-deterministic) view — `instanceId`, `isReplaying`, `getInput`, `newUuid`. Everything that schedules durable work or ends/restarts the run (`callActivity*`, `createTimer`, `waitForExternalEvent*`, `complete`, `continueAsNew`) is mutating and stays on `WorkflowContext` — no separate `WorkflowScheduler` / `WorkflowCompletion`.

### 9.2 Capabilities we will NOT authority-split

For the following, a sub-trait is *possible* but tracks **request-shape, not privilege** — the narrow view grants no less authority than the full one, so splitting buys nothing. **Decision: these stay as single traits; we will not authority-split them.** (Their *name*-tier splits in §8 are unaffected — this decision is only about the authority axis.)

| Capability | Would-be axis | Why it is not a real authority boundary | Decision |
|---|---|---|---|
| `PublishCapability` | single vs `bulkPublish` | all three methods write to the same component; bulk is a throughput variant, not more authority | **not split** |
| `BindingsCapability` | `invoke` vs `invokeOneWay` | response-shape (awaits a reply vs fire-and-forget), not privilege | **not split** |
| `LockCapability` | `tryLock` vs `unlock` | a matched acquire/release pair — you always hold both together | **not split** |
| `InvokeCapability` | no-body GET vs body+verb | HTTP-shape; the meaningful narrowing is by *target app* (name tier, §5.6), not by method | **not split** |
| `ActorCapability` | `invoke` vs `invokeVoid` | actor-method semantics are user-defined, so the capability cannot classify a call as read or write | **not split** |
| `ConversationCapability` | — | a single `converse` method; nothing to divide | **not split** |

### 9.3 Summary of the authority axes

```mermaid
mindmap
  root((Authority split))
    Will split - read-only base
      State - ReadState then StateCapability
      Secrets - SecretReader then SecretsCapability, getBulk
      Configuration - ConfigReader then ConfigurationCapability, subscribe
      Jobs - JobReader then JobsCapability
      WorkflowContext - WorkflowInfo then WorkflowContext
    Will split - lineages
      Crypto - Encrypt and Decrypt, asymmetric
      Workflow client - WorkflowObserver then WorkflowController, plus WorkflowLauncher
      ActorContext state - ReadActorState then WriteActorState
      ActorContext callbacks - ScheduleCallbacks then ManageCallbacks
    Will NOT split
      Publish
      Bindings
      Lock
      Invoke
      Actor
      Conversation
```

---

## 10. Caveats

- **Interface-enforced, not CC-enforced** (see §2c). Don't rely on capture checking to stop a holder reaching a method the narrow type omits; rely on the type omitting it.
- This document was derived from the trait/companion definitions, not every `*Impl`. A split's feasibility could be constrained by an impl detail (e.g. a shared mutable handle); for the pure-subtyping authority splits that's unlikely.
- "Worth it" is judged from the API shape. It should be confirmed against real consumer call sites (the examples repo) — which tiers code would actually request — before committing to the name-tier ones.
