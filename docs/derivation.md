implement derivation of classes from traits

the point is to simplify the using of dapr4s.
currently the users have to create the reified model which is tedious

```scala
cap.invoke(AppId("svc-invoke-test"), MethodName("double"), IncrRequest(5))[CounterState]
```

instead, it would be nicer to be able to have
```scala
@ServiceInvocationDerivation
trait MyService {
  def double(
    input: IncrRequest,
    httpMethod: HttpMethod = HttpMethod.Post,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty
  )(using ServiceInvocationCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
}
```
and have it generate a class that would look something like this
```scala
class MyServiceImpl(appId: AppId) extends MyService {
  def double(
    input: IncrRequest,
    httpMethod: HttpMethod = HttpMethod.Post,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty
  )(using cap: ServiceInvocationCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState =
    cap.invoke(appId, MethodName("double"), input, httpMethod, metadata)[CounterState]
}
```

We coudld do this for
* StateCapability#get, StateCapability#save, Scala method name represents StateKey (use the `def x = ???` and `def x_=(value: Value) = ???`) trick
* PubSubCapability#publish, Scala method name represents Topic
* ServiceInvocationCapability#invoke (both overloads), Scala method name represents InvocationMethodName
* SecretsCapability#get, Scala method name represents SecretKey
* ConfigurationCapability#get, Scala method name represents a single ConfigKey
* BindingsCapability#invoke and BindingsCapability#invokeOneWay, Scala method name represents BindingOperation, very similar to ServiceInvocationCapability
* ActorCapability#invoke (both overloads) and ActorCapability#invokeVoid, Scala method name represents ActorMethodName, very similar to ServiceInvocationCapability
* WorkflowCapability#start, WorkflowCapability#startWithId (all overloads), Scala method name represents WorkflowName
* CryptoCapability#encrypt and CryptoCapability#encryptString, Scala method name represents CryptoKeyName
* JobsCapability#schedule, JobsCapability#scheduleOnce and JobsCapability#get, Scala method name represents JobName

* ActorContext#get and ActorContext#set, Scala method name represents StateKey, similar to StateCapability
* WorkflowContext#waitForExternalEvent (both overloads), Scala method name represents EventName

Note that Scala allows for method overloading, so each overload can call different method of the capability.

Also try to think of a way to transform an ordinary class, like

```scala
@ActorDefinitionDerivation
abstract class Counter {
  private def actorId: ActorId

  def increment(input: IncrRequest)(using ActorContext): CounterState = ???
  def get()(using ActorContext): CounterState = ???
  def reset()(using ActorContext): CounterState = ???
  def scheduleReset()(using ActorContext): Unit = ???
  def cancelReset()(using ActorContext): Unit = ???
  def scheduleQuickReset()(using ActorContext): Unit = ???
  def scheduleAutoIncrement()(using ActorContext): Unit = ???

  @reminder
  def scheduledReset(input: String)(using ActorContext): CounterState = ???

  @timer
  def autoIncrement(input: IncrRequest)(using ActorContext): CounterState = ???
}
```

into the reified representation that dapr4s uses, this would be

```scala
ActorDefinition(ActorType("Counter")) { actorId =>
  ActorRoutes(
    methods = List(
      ActorMethodRoute[IncrRequest, CounterState](ActorMethodName("increment"))(increment),
      ActorMethodRoute[Unit, CounterState](ActorMethodName("get"))(get),
      ActorMethodRoute[Unit, CounterState](ActorMethodName("reset"))(reset),
      ActorMethodRoute[Unit, Unit](ActorMethodName("scheduleReset"))(scheduleReset),
      ActorMethodRoute[Unit, Unit](ActorMethodName("cancelReset"))(cancelReset),
      ActorMethodRoute[Unit, Unit](ActorMethodName("scheduleQuickReset"))(scheduleQuickReset),
      ActorMethodRoute[Unit, Unit](ActorMethodName("scheduleAutoIncrement"))(scheduleAutoIncrement),
    ),
    reminders = List(
      ActorReminderRoute[String](ReminderName("onScheduledReset"))(msg => onScheduledReset(msg)),
    ),
    timers = List(
      ActorTimerRoute[IncrRequest](TimerName("onAutoIncrement"))(req => onAutoIncrement(req)),
    ),
  )
}
```

Also, is there some opportunity to improve something for Workflows using this technique?
Maybe something like

```scala
trait MyWorkflowSchema[F[_]] {
  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): F[CounterState]
}

@WorkflowActivityDerivation
class MyWorkflowActivity extends MyWorkflowSchema[Id] {
  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState =
   CounterState(input.amount * 2)
}
```

would then turn into the dapr4s reified model

```scala
object MyWorkflowActivity extends MyWorkflowSchema[Task] {
  class AddActivity(JsonCodec[IncrRequest], JsonCodec[CounterState]) extends WorkflowActivity[IncrRequest, CounterState]:
    def execute(input: IncrRequest)(using DaprCapability): CounterState =
      CounterState(input.amount * 2)

  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): Task[CounterState] =
    WorkflowContext.callActivity[AddActivity](input)
}
```

Use the llm-wiki info about Scala 3 macros and derivations and meta-programming and also reference how other libraries do it (it's all in the wiki).
Then implement it.
It should go into its own package, so `dapr4s.derivation.*`.

Consider also the viability of each sub-experiment in how it will influence dapr4s-examples.
At the end, every improvement we make in dapr4s must work with darp4s-examples (but you may change the codebases significantly to make it possible).

---

# Refined design (2026-06-07)

This section records the design decided through interview before implementation.
It supersedes the loose sketch above where they disagree.

## Decisions

1. **Mechanism — inline `derive` engine first, annotation sugar on top.**
   The real engine is a *transparent inline macro* entry point that returns an
   instance of the user's trait:

   ```scala
   import dapr4s.derivation.*
   val svc: MyService = ServiceInvocation.derive[MyService](AppId("greeting-service"))
   ```

   The `@ServiceInvocationDerivation` annotation (Scala 3 experimental
   `MacroAnnotation`) is *thin sugar*: it adds a companion `derive`/`apply` that
   delegates to the inline engine. The engine must stand on its own; the
   annotation is optional convenience.

2. **Scope of this iteration — one vertical slice.**
   Only `ServiceInvocationCapability` *client-side* derivation. It is built end
   to end: macro + compile-time validation + Docker-free unit test + wired into a
   dapr4s test app that runs under capture-checking and safe mode. The other
   capabilities (State, PubSub, Secrets, Config, Bindings, Actor, Workflow,
   Crypto, Jobs, ActorContext, WorkflowContext) and the server-side reification
   (`@ActorDefinitionDerivation`, `@WorkflowActivityDerivation`) follow in later
   iterations once the skeleton is proven. They are explicitly **out of scope**
   here.

3. **Name mapping — verbatim, with `@name` override.**
   The Scala method name is used as the `InvocationMethodName` exactly as
   written (`double` → `"double"`). A per-method
   `@dapr4s.derivation.name("custom-wire-name")` annotation overrides it when a
   different wire name is required. No automatic case conversion.

4. **Validation against examples — dapr4s test apps first.**
   The slice is proven inside `dapr4s`'s own `test/integration/apps` before the
   separate `dapr4s-examples` repo is touched.

## Why the trait must be "faithful" (capture-checking)

`ServiceInvocationCapability` is an `ExclusiveCapability`. The capture checker
forbids storing it in the derived instance, so it cannot be captured at
`derive` time. Therefore the capability — and likewise the `JsonCodec`s — must
arrive *per call* via each method's `using` clause, exactly as the doc sketch
shows. This is not a stylistic choice; it is forced by CC. As a consequence the
derived instance captures only the plain `AppId` value (empty capability
capture set), so it needs no `@scala.caps.assumeSafe` and is freely storable.

The macro's whole job per method is therefore:

* read the declared **return type** as `Resp`,
* read the first value parameter's type (if any) as the request body `Req`,
* pick the right `invoke` overload,
* forward the value args and the in-scope `using` givens (capability + codecs).

## Method contract (enforced at compile time)

A derivable method on the trait must have one of these shapes:

**Body-bearing** (maps to `invoke[Req](appId, method, data, httpMethod, metadata)[Resp]`):

```scala
def <name>(
  <bodyParam>: <Req>,
  httpMethod: HttpMethod = HttpMethod.Post,          // OPTIONAL
  metadata: Map[MetadataKey, MetadataValue] = Map.empty,  // OPTIONAL
)(using ServiceInvocationCapability, JsonCodec[<Req>], JsonCodec[<Resp>]): <Resp>
```

**No-body** (maps to `invoke[Resp](appId, method)`):

```scala
def <name>()(using ServiceInvocationCapability, JsonCodec[<Resp>]): <Resp>
```

Rules enforced by the macro (clear compile error otherwise):

* The **first** value parameter (if present) is the request body. Its type is
  free — it is the only thing that varies.
* `httpMethod` and `metadata`, *if present*, must match **name, type and
  position** exactly: `httpMethod: HttpMethod` then
  `metadata: Map[MetadataKey, MetadataValue]`, in that order, after the body.
  Either or both may be omitted; when omitted the corresponding default
  (`HttpMethod.Post`, `Map.empty`) is supplied by the macro.
* The using clause must provide a `ServiceInvocationCapability`. The required
  `JsonCodec`s (`JsonCodec[Req]` when there is a body, `JsonCodec[Resp]` always)
  must be resolvable — they are taken from the using clause / ambient givens.
* Only abstract methods are derived. A concrete (already-implemented) member is
  left as-is. Non-method abstract members are rejected.

## Entry point

```scala
package dapr4s.derivation

object ServiceInvocation:
  transparent inline def derive[T](appId: AppId): T = ${ … }
```

`derive` returns an anonymous class `extends T`, generated with
`quotes.reflect.Symbol.newClass`, with one `DefDef` per abstract method whose
body forwards to the `ServiceInvocationCapability` companion. The `appId`
argument is captured by reference (it is a plain value, CC-safe).

## Sugar — `ServiceInvocation.Derived` mixin (not a macro annotation)

The doc sketched a `@ServiceInvocationDerivation` macro annotation that would synthesise a
companion `derive`. **That form was prototyped and rejected.** Scala 3 macro annotations
(`scala.annotation.MacroAnnotation`) expand in a phase that runs *after* the typer, so any
member they generate is invisible to references in the *same* compilation run — both
synthesising a fresh companion and augmenting an existing one failed (`value derive is not a
member` / `Not found`). The annotation would only deliver usable sugar to a *downstream*,
separately-compiled module, which is a footgun for same-module use.

The shipped sugar is an `inline` mixin instead. `derive` is a genuine inherited member at
typer time (only its body expands later), so it resolves within the same compilation run:

```scala
trait MyService:
  def double(input: IncrRequest)(using ServiceInvocationCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
object MyService extends ServiceInvocation.Derived[MyService]

val svc = MyService.derive(AppId("doubler"))
```

`Derived[T]` is just `trait Derived[T] { inline def derive(appId: AppId): T = ServiceInvocation.derive[T](appId) }`.

## How the generated body is built (implementation note)

The macro creates the impl class with `Symbol.newClass` and one `DefDef` per abstract
method. Two non-obvious constraints shaped the body:

1. *No synthesised `given`s in the generated body.* A first attempt introduced
   `given JsonCodec[...] = <param>` locals inside each method; the compiler lifted them and
   captured them into the enclosing class — the host test class ended up with a 30-argument
   constructor and munit could not instantiate it. The fix routes every call through a tiny
   hand-written runtime forwarder (`ServiceInvocationDerivationRuntime.invokeBody/invokeNoBody`)
   that takes the capability and codecs as plain explicit arguments. This also removes any
   need to reconstruct `invoke`'s interleaved type/`using` clause order by hand, and dissolves
   the `Req == Resp` ambiguity (the codecs are arguments, not givens).
2. *The capability is never rebound.* Capture-checking forbids an `ExclusiveCapability`
   flowing into a fresh `given` root, so the capability `using` parameter is passed straight
   through as an argument.

## Testing

* **Unit (no Docker, `dapr4s.test.unit.*`):** a hand-written fake
  `ServiceInvocationCapability` records `(appId, method, data, httpMethod,
  metadata)` and the requested `Resp`. Derive a small trait, call its methods
  with stub codecs, assert the recorded values — proves name mapping, overload
  selection, and argument forwarding.
* **Integration:** refactor a caller in `test/integration/apps` to obtain its
  remote calls through a derived service, confirming the slice compiles under
  `language.experimental.safe` + capture checking and behaves identically over a
  real sidecar.

---

# Implementation status — full build (2026-06-07)

After the ServiceInvocation slice was proven, the rest of the doc's list was implemented.
This section records what shipped, how, and what didn't work.

## What shipped

All in package `dapr4s.derivation`. Every client facade follows the same shape: an `object X`
with `inline def derive[…]: T`, an `X.Derived[T]` inline mixin, and a private macro built on the
shared `MacroSupport` scaffold; each forwards through `@assumeSafe` runtime helpers
(`Forwarders` / `ServiceInvocationDerivationRuntime`). Method name → Dapr name is **verbatim**,
overridable per member with `@name`.

| Engine | Capability | Method → name | Notes |
|---|---|---|---|
| `ServiceInvocation.derive[T](appId)` | `ServiceInvocationCapability` | `InvocationMethodName` | body + optional `httpMethod`/`metadata`; no-body overload |
| `Bindings.derive[T]` | `BindingsCapability` | `BindingOperation` | `Option[Resp]`→`invoke`, `Unit`→`invokeOneWay` |
| `Actor.derive[T]` | `ActorCapability` | `ActorMethodName` | body / no-body / `invokeVoid` (no-body `Unit`) |
| `PubSub.derive[T]` | `PubSubCapability` | `Topic` | `publish` / `publishWithMetadata` |
| `Secrets.derive[T]` | `SecretsCapability` | `SecretKey` | `Option[SecretValue]`, optional `metadata` |
| `Configuration.derive[T]` | `ConfigurationCapability` | `ConfigKey` | single-key `Option[ConfigItem]` |
| `Crypto.derive[T]` | `CryptoCapability` | `CryptoKeyName` | `encrypt` (bytes) / `encryptString` (String) |
| `Jobs.derive[T]` | `JobsCapability` | `JobName` | `schedule` / `scheduleOnce` / `get` |
| `Workflow.derive[T]` | `WorkflowCapability` | `WorkflowName` | `start`/`startWithId` × input |
| `State.derive[T]` | `StateCapability` | `StateStoreKey` | getter/setter (`def x` / `def x_=`) |
| `ActorState.derive[T]` | `ActorContext` | `ActorStateKey` | getter/setter |
| `WorkflowEvents.derive[T]` | `WorkflowContext` | `EventName` | `waitForExternalEvent`, returns `Task[T]^{ctx}` |
| `ActorDefinitions.derive[C]` | — (server-side) | `ActorType` + route names | reifies an actor class to `ActorDefinition` |

Each has a Docker-free unit test (`CapabilityDerivationTest`, `ServiceInvocationDerivationTest`,
`WorkflowEventsTest`, `ActorDefinitionsTest`) using recording fakes, plus the ServiceInvocation
real-sidecar round-trip.

## The shared scaffold (`MacroSupport`)

`deriveTrait[T](engine)(bodyFn)` owns the common skeleton: validate `T` is a trait of abstract
methods, synthesise `T$Derived` via `Symbol.newClass`, and emit one `DefDef` per method whose
body is built by the capability's `bodyFn`. The `bodyFn` callback is typed against the same
`Quotes` instance (`(using q: Quotes)(bodyFn: (q.reflect.Symbol, …) => q.reflect.Term)`), which
is what lets the path-dependent reflection types line up across the call. Helpers `paramInfo`
(name/ref/type/given per parameter), `wireName`/`nameOverride`, `jsonCodecArg`, `optionArg`,
`isUnit`, and `fail` cover the per-method analysis. This kept each of the 11 client engines to
~40–80 lines.

## Getter/setter convention (State, ActorContext)

A member whose Scala name ends in `_=` is the setter (`save`/`set`); otherwise it is the getter
(`get`, returning `Option[T]`). The key is the name with `_=` stripped (so `def count` and
`def count_=` share key `"count"`), and `@name` overrides it independently of setter detection.
`svc.count = 5` / `val n = svc.count` then read and write Dapr state.

## Server-side actor reification (`ActorDefinitions.derive[C]`)

Turns an actor *class* into a `dapr4s.ActorDefinition`. The `ActorType` is the class's simple
name (or `@name`). Methods with a `(using ActorContext)` clause become routes:
`@reminder`/`@timer` → reminder/timer routes (result discarded), everything else → method
routes. Input type is the first value parameter (or `Unit`); output is the return type;
`JsonCodec[I]`/`JsonCodec[O]` are **summoned at the `derive` call site** (not declared on the
method).

How it is built: the macro emits
`ActorDefinition(ActorType(name)) { (id: ActorId) => (ctx: ActorContext) ?=> <routes> }` as a
quote, using the `'{ (id) => … ${ f('id, 'ctx) } }` technique to obtain `id`/`ctx` as `Expr`s
inside the nested splice. It then `new C(id)` (the class must have a no-arg or single-`ActorId`
constructor) and, per method, builds a handler via `quotes.reflect.Lambda` that calls
`inst.m(in)(using ctx)` — applying each parameter clause explicitly so the `ActorContext` is
threaded in by hand rather than by implicit search. Handlers are stored through the existing
`@assumeSafe` route factories (via `Forwarders.actorMethodRoute/...`), whose `AnyRef` erasure
absorbs the `ctx` capture, so no capture-checking annotations leak into generated code.

**Deviation from the doc sketch:** the doc wrote `private def actorId: ActorId`. A `private`
member cannot be overridden by a synthesised subclass, so the contract is instead a constructor
parameter (`class Counter(actorId: ActorId)`) — the macro does `new C(id)`.

## What did not work / was not implemented

* **`@ServiceInvocationDerivation` and any macro-annotation form.** Scala 3 `MacroAnnotation`
  expands *after* the typer, so members it generates are invisible to references compiled in the
  same run (verified: both synthesising and augmenting a companion fail with `value derive is not
  a member`). Replaced everywhere by the `X.Derived[T]` inline mixin, which is a genuine
  inherited member at typer time. The annotation could only ever work for a downstream,
  separately-compiled consumer.

* **`@WorkflowActivityDerivation` (workflow-activity reification).** Not implemented. It hits the
  same macro-annotation wall *and* is structurally worse: its transformation must synthesise a
  `WorkflowActivity[I, O]` *type* that the orchestration then references by name
  (`WorkflowContext.callActivity[GeneratedActivity]`). A generated type referenced elsewhere in
  the same module cannot be surfaced at typer time, and the transformation is not expressible as
  a single-value `derive[T]: X` either (it produces a class + registrations + a schema-implementing
  object). Left as designed-but-deferred; the manual `WorkflowActivity` + `callActivity` API
  remains the way to write activities.

* **Local `given`s in generated bodies (all engines).** Synthesising
  `given JsonCodec[…] = <param>` inside generated methods caused the compiler to lift and capture
  them into the enclosing class (a 30-argument constructor on the host test class; munit could
  not instantiate it). Every engine therefore routes through `@assumeSafe` runtime forwarders
  that take the capability and codecs as plain explicit arguments — no generated givens, and the
  capability `using` parameter is passed straight through (capture-checking forbids rebinding an
  `ExclusiveCapability` into a fresh `given`).

* **dapr4s-examples.** Per the agreed plan, the slice was proven inside dapr4s's own test apps;
  the separate `dapr4s-examples` repo was intentionally left untouched in this pass.
