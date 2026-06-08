implement derivation of classes from traits

the point is to simplify the using of dapr4s.
currently the users have to create the reified model which is tedious

```scala
cap.invoke(AppId("svc-invoke-test"), MethodName("double"), IncrRequest(5))[CounterState]
```

instead, it would be nicer to be able to have
```scala
@InvokeDerivation
trait MyService {
  def double(
    input: IncrRequest,
    httpMethod: HttpMethod = HttpMethod.Post,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty
  )(using InvokeCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
}
```
and have it generate a class that would look something like this
```scala
class MyServiceImpl(appId: AppId) extends MyService {
  def double(
    input: IncrRequest,
    httpMethod: HttpMethod = HttpMethod.Post,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty
  )(using cap: InvokeCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState =
    cap.invoke(appId, MethodName("double"), input, httpMethod, metadata)[CounterState]
}
```

We coudld do this for
* StateCapability#get, StateCapability#save, Scala method name represents StateKey (use the `def x = ???` and `def x_=(value: Value) = ???`) trick
* PublishCapability#publish, Scala method name represents Topic
* InvokeCapability#invoke (both overloads), Scala method name represents InvokeMethodName
* SecretsCapability#get, Scala method name represents SecretKey
* ConfigurationCapability#get, Scala method name represents a single ConfigurationKey
* BindingsCapability#invoke and BindingsCapability#invokeOneWay, Scala method name represents BindingOperation, very similar to InvokeCapability
* ActorCapability#invoke (both overloads) and ActorCapability#invokeVoid, Scala method name represents ActorMethodName, very similar to InvokeCapability
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
   val svc: MyService = Invoke.derive[MyService](AppId("greeting-service"))
   ```

   The `@InvokeDerivation` annotation (Scala 3 experimental
   `MacroAnnotation`) is *thin sugar*: it adds a companion `derive`/`apply` that
   delegates to the inline engine. The engine must stand on its own; the
   annotation is optional convenience.

2. **Scope of this iteration — one vertical slice.**
   Only `InvokeCapability` *client-side* derivation. It is built end
   to end: macro + compile-time validation + Docker-free unit test + wired into a
   dapr4s test app that runs under capture-checking and safe mode. The other
   capabilities (State, Publish, Secrets, Config, Bindings, Actor, Workflow,
   Crypto, Jobs, ActorContext, WorkflowContext) and the server-side reification
   (`@ActorDefinitionDerivation`, `@WorkflowActivityDerivation`) follow in later
   iterations once the skeleton is proven. They are explicitly **out of scope**
   here.

3. **Name mapping — verbatim, with `@name` override.**
   The Scala method name is used as the `InvokeMethodName` exactly as
   written (`double` → `"double"`). A per-method
   `@dapr4s.derivation.name("custom-wire-name")` annotation overrides it when a
   different wire name is required. No automatic case conversion.

4. **Validation against examples — dapr4s test apps first.**
   The slice is proven inside `dapr4s`'s own `test/integration/apps` before the
   separate `dapr4s-examples` repo is touched.

## Why the trait must be "faithful" (capture-checking)

`InvokeCapability` is an `ExclusiveCapability`. The capture checker
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
)(using InvokeCapability, JsonCodec[<Req>], JsonCodec[<Resp>]): <Resp>
```

**No-body** (maps to `invoke[Resp](appId, method)`):

```scala
def <name>()(using InvokeCapability, JsonCodec[<Resp>]): <Resp>
```

Rules enforced by the macro (clear compile error otherwise):

* The **first** value parameter (if present) is the request body. Its type is
  free — it is the only thing that varies.
* `httpMethod` and `metadata`, *if present*, must match **name, type and
  position** exactly: `httpMethod: HttpMethod` then
  `metadata: Map[MetadataKey, MetadataValue]`, in that order, after the body.
  Either or both may be omitted; when omitted the corresponding default
  (`HttpMethod.Post`, `Map.empty`) is supplied by the macro.
* The using clause must provide a `InvokeCapability`. The required
  `JsonCodec`s (`JsonCodec[Req]` when there is a body, `JsonCodec[Resp]` always)
  must be resolvable — they are taken from the using clause / ambient givens.
* Only abstract methods are derived. A concrete (already-implemented) member is
  left as-is. Non-method abstract members are rejected.

## Entry point

```scala
package dapr4s.derivation

object Invoke:
  transparent inline def derive[T](appId: AppId): T = ${ … }
```

`derive` returns an anonymous class `extends T`, generated with
`quotes.reflect.Symbol.newClass`, with one `DefDef` per abstract method whose
body forwards to the `InvokeCapability` companion. The `appId`
argument is captured by reference (it is a plain value, CC-safe).

## Calling the engine — a plain factory (not a macro annotation, not a mixin)

The doc sketched a `@InvokeDerivation` macro annotation that would synthesise a
companion `derive`. **That form was prototyped and rejected.** Scala 3 macro annotations
(`scala.annotation.MacroAnnotation`) expand in a phase that runs *after* the typer, so any
member they generate is invisible to references in the *same* compilation run — both
synthesising a fresh companion and augmenting an existing one failed (`value derive is not a
member` / `Not found`). The annotation would only deliver usable sugar to a *downstream*,
separately-compiled module, which is a footgun for same-module use.

An interim `X.Derived[T]` `inline` mixin was then shipped, but it added a layer with no
value over calling `X.derive[T]` directly. It has been removed. The engine's `inline def
derive[T]` already expands at any concrete-type call site, so the idiomatic form is a plain
top-level factory next to the trait — a `lazy val` when `derive` is parameterless (it caches
the single facade instance), or a `def` for `Invoke`, whose `derive` takes the
target `appId`:

```scala
trait MyService:
  def double(input: IncrRequest)(using InvokeCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
def MyService(appId: AppId): MyService = Invoke.derive[MyService](appId)

val svc = MyService(AppId("doubler"))
```

```scala
trait OrderEvents:
  def orders(event: OrderEvent)(using PublishCapability, JsonCodec[OrderEvent]): Unit
lazy val OrderEvents: OrderEvents = Publish.derive[OrderEvents]   // parameterless → cached lazy val
```

The trait name (a type) and the factory name (a term) share an identifier the same way a
class and its companion object do, so there is no clash. The macro expands at the factory's
body, where `T` is concrete; a `lazy val` cannot itself be `inline`, but it does not need to
be — only the engine `derive` it calls is.

**Caveat — where `derive` summons implicits.** The client facades take their `JsonCodec`s as
per-method `using` parameters, so `derive` summons nothing and a top-level `lazy val` is always
fine. But `WorkflowActivities.derive[C]` and `WorkflowActivityCalls.derive[Calls, Impl]` summon
`JsonCodec[I]`/`JsonCodec[O]` **at the `derive` site**. Put those calls where those codecs are in
scope — typically inside the workflow body (whose constructor threads the codecs in), not in a
top-level `lazy val` where they may be absent.

## How the generated body is built (implementation note)

The macro creates the impl class with `Symbol.newClass` and one `DefDef` per abstract
method. Two non-obvious constraints shaped the body:

1. *No synthesised `given`s in the generated body.* A first attempt introduced
   `given JsonCodec[...] = <param>` locals inside each method; the compiler lifted them and
   captured them into the enclosing class — the host test class ended up with a 30-argument
   constructor and munit could not instantiate it. The fix routes every call through a tiny
   hand-written runtime forwarder (`InvokeDerivationRuntime.invokeBody/invokeNoBody`)
   that takes the capability and codecs as plain explicit arguments. This also removes any
   need to reconstruct `invoke`'s interleaved type/`using` clause order by hand, and dissolves
   the `Req == Resp` ambiguity (the codecs are arguments, not givens).
2. *The capability is never rebound.* Capture-checking forbids an `ExclusiveCapability`
   flowing into a fresh `given` root, so the capability `using` parameter is passed straight
   through as an argument.

## Testing

* **Unit (no Docker, `dapr4s.test.unit.*`):** a hand-written fake
  `InvokeCapability` records `(appId, method, data, httpMethod,
  metadata)` and the requested `Resp`. Derive a small trait, call its methods
  with stub codecs, assert the recorded values — proves name mapping, overload
  selection, and argument forwarding.
* **Integration:** refactor a caller in `test/integration/apps` to obtain its
  remote calls through a derived service, confirming the slice compiles under
  `language.experimental.safe` + capture checking and behaves identically over a
  real sidecar.

---

# Implementation status — full build (2026-06-07)

After the Invoke slice was proven, the rest of the doc's list was implemented.
This section records what shipped, how, and what didn't work.

## What shipped

All in package `dapr4s.derivation`. Every client facade follows the same shape: an `object X`
with `inline def derive[…]: T` and a private macro built on the
shared `MacroSupport` scaffold; each forwards through `@assumeSafe` runtime helpers
(`Forwarders` / `InvokeDerivationRuntime`). Method name → Dapr name is **verbatim**,
overridable per member with `@name`.

| Engine | Capability | Method → name | Notes |
|---|---|---|---|
| `Invoke.derive[T](appId)` | `InvokeCapability` | `InvokeMethodName` | body + optional `httpMethod`/`metadata`; no-body overload |
| `Bindings.derive[T]` | `BindingsCapability` | `BindingOperation` | `Option[Resp]`→`invoke`, `Unit`→`invokeOneWay` |
| `Actor.derive[T]` | `ActorCapability` | `ActorMethodName` | body / no-body / `invokeVoid` (no-body `Unit`) |
| `Publish.derive[T]` | `PublishCapability` | `Topic` | `publish` / `publishWithMetadata` |
| `Secrets.derive[T]` | `SecretsCapability` | `SecretKey` | `Option[SecretValue]`, optional `metadata` |
| `Configuration.derive[T]` | `ConfigurationCapability` | `ConfigurationKey` | single-key `Option[ConfigurationItem]` |
| `Crypto.derive[T]` | `CryptoCapability` | `CryptoKeyName` | `encrypt` (bytes) / `encryptString` (String) |
| `Jobs.derive[T]` | `JobsCapability` | `JobName` | `schedule` / `scheduleOnce` / `get` |
| `Workflow.derive[T]` | `WorkflowCapability` | `WorkflowName` | `start`/`startWithId` × input |
| `State.derive[T]` | `StateCapability` | `StateStoreKey` | getter/setter (`def x` / `def x_=`) |
| `ActorState.derive[T]` | `ActorContext` | `ActorStateKey` | getter/setter |
| `WorkflowEvents.derive[T]` | `WorkflowContext` | `EventName` | `waitForExternalEvent`, returns `Task[T]^{ctx}` |
| `ActorDefinitions.derive[C]` | — (server-side) | `ActorType` + route names | reifies an actor class to `ActorDefinition` |

Each has a Docker-free unit test (`CapabilityDerivationTest`, `InvokeDerivationTest`,
`WorkflowEventsTest`, `ActorDefinitionsTest`) using recording fakes, plus the Invoke
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

* **`@InvokeDerivation` and any macro-annotation form.** Scala 3 `MacroAnnotation`
  expands *after* the typer, so members it generates are invisible to references compiled in the
  same run (verified: both synthesising and augmenting a companion fail with `value derive is not
  a member`). Replaced everywhere by a plain `X.derive[T]` factory (`lazy val`/`def`) next to
  the trait, which resolves at typer time. The annotation could only ever work for a downstream,
  separately-compiled consumer.

* **`@WorkflowActivityDerivation` (workflow-activity reification).** ~~Not implemented.~~ **Shipped
  in a revised shape** — see "Workflow-activity reification (2026-06-07)" below. The original blocker
  ("must synthesise a `WorkflowActivity[I, O]` *type* referenced by name") rested on a false premise:
  activity dispatch is by **string**, not type, so no generated type is needed. The narrower truth
  that remains: a *generated, typed caller facade* cannot be surfaced without a user-written trait
  (every trait-free alternative is compiler-blocked, below), so the caller uses a small user trait
  like every other client engine; the implementation stays a plain class.

* **Local `given`s in generated bodies (all engines).** Synthesising
  `given JsonCodec[…] = <param>` inside generated methods caused the compiler to lift and capture
  them into the enclosing class (a 30-argument constructor on the host test class; munit could
  not instantiate it). Every engine therefore routes through `@assumeSafe` runtime forwarders
  that take the capability and codecs as plain explicit arguments — no generated givens, and the
  capability `using` parameter is passed straight through (capture-checking forbids rebinding an
  `ExclusiveCapability` into a fresh `given`).

* **dapr4s-examples.** Per the agreed plan, the slice was proven inside dapr4s's own test apps;
  the separate `dapr4s-examples` repo was intentionally left untouched in this pass.

---

# Server-route derivation (0.11.0)

To let a server app be written without hand-listing its routes, two more engines reify the
*inbound* side from a handler type (an `object` of handlers, or a class with a no-arg ctor):

- `InvokeRoutes.derive[T]: List[InvokeRoute]` — each method → an `InvokeRoute`
  (name verbatim or `@name` → `InvokeMethodName`; first value param = request, return = response).
- `Subscriptions.derive[T](pubsubName): List[Subscription]` — each method (taking `CloudEvent[P]`,
  returning `SubscriptionResult`) → a `Subscription`; name → `Topic`, `@deadLetter("…")` sets the
  dead-letter topic.

**How the ambient capabilities/codecs are resolved.** Unlike the client facades (where the
capability/codecs are the derived method's own `using` params, available as refs), a server
handler's `using` dependencies and the route's `JsonCodec`s are *ambient* at the `derive` call
site (inside the `DaprCapability.…` block, with codecs from the shell). The macro resolves them
with **`Expr.summon[T]`** — which searches at the macro-*expansion* site, i.e. the call site.
(`summon[T]` *inside a quote* does not work: it is searched at macro-*definition* time and fails.)
Each handler is built as a `quotes.reflect.Lambda` whose body applies the method's value arg and
fills every `using` clause with an `Expr.summon`-ed instance; the lambda is stored through the
existing `@assumeSafe` route factories, whose `AnyRef` erasure absorbs the captured capabilities.

This unblocks fully-derived server apps (no reified routes). Workflow **activities** are now derivable
too (see the next section) — defined as a plain class and reified to `WorkflowActivity` values, called
through a derived typed facade. The only construct that still has no derived form is the workflow
**orchestration** itself (`extends Workflow` + `run`), which stays reified.

---

# Workflow-activity reification (2026-06-07)

This supersedes the "deferred" note above. Workflow activities can now be **defined as a plain class**
(no `extends WorkflowActivity[I, O]`, no manual registration) and **called through a typed facade**.

## The key realisation

Activity dispatch is by **string**, not type. The server registers each activity under a name
(`registerActivity(name, …)`) and a workflow schedules it by the same name (`ctx.callActivity(name, …)`).
The existing `callActivity[A]` / `ActivityDef[A]` / `ClassTag` machinery is just *one* way to produce
that string (the canonical class name). So the doc's old blocker — "must synthesise a *type* referenced
elsewhere by name" — dissolves: nothing needs a generated type.

## What was proven impossible (so the design avoids it)

A *generated, typed, named* caller facade with no user-written trait is not expressible on this toolchain
(tested on `3.9` and `3.10` nightlies, project flags). Three independent walls:

1. **`F[_]` shared trait** (`MyWorkflowSchema[F[_]]`, impl `extends …[Id]`, caller `extends …[Task]`):
   an abstract `F[X]` return type is assumed by capture-checking to capture the root capability `any`, so
   a clean (empty-capture) impl class cannot implement it (`Reference 'any' is not included in the allowed
   capture set {}`). `Task[O]^{ctx}` also can never equal `F[O]` for a fixed `F` — the capture references a
   per-call parameter. (Plus a nightly override-check crash, `AssertionError: ContextualMethodType …`.)
2. **`transparent inline` returning an anonymous class** (no trait): the structural members widen to
   `Object` — `value addActivity is not a member of Object`.
3. **Macro annotation generating `object MyWorkflowActivity`**: `macro annotation can not add top-level
   object`; and any nested/augmented member is invisible to same-compilation references anyway.

⇒ The caller surface needs a user-written trait, exactly like every other client engine.

## What shipped

Two engines, mirroring the existing server/client split (`ActorDefinitions` vs the client facades):

* **Server — `WorkflowActivities.derive[C]: List[WorkflowActivity[?, ?]]`.**
  Reifies a plain class `C` (no-arg constructor) into one `WorkflowActivity` per `(using DaprCapability)`
  method, ready for `DaprApp.activities`. Input = first value parameter (or `Unit`); output = return type;
  `JsonCodec[I]`/`JsonCodec[O]` summoned at the `derive` site. Any *extra* `using` params on a method beyond
  `DaprCapability` (e.g. the `JsonCodec`s a body needs for nested service-invocation / pub/sub calls) are also
  summoned at the `derive` site and threaded in — so activities that do cross-service I/O work. Each is registered
  under
  `<fully-qualified-class>#<method-wire-name>` (`@name` overrides the method part). Built like
  `ActorDefinitions`: one shared `new C` instance, a per-method handler `(in, d) => inst.m(in)(using d)`
  built as a **quote** (so no synthesised function type needs `asExprOf`, and no `asInstanceOf` leaks into
  the expanded safe-mode code), passed through the `@assumeSafe` `Forwarders.workflowActivity`, which
  capture-erases the handler to `AnyRef` (the `Subscription.rawHandler` trick) so the activity has an empty
  capture set and lives in a plain `List`.

* **Client — `WorkflowActivityCalls.derive[Calls, Impl]: Calls`.**
  Implements a user trait `Calls` of methods `def m(input: I)(using ctx: WorkflowContext): Task[O]^{ctx}`
  (the `Task` captures the per-call context, exactly like `WorkflowEvents`). Each forwards to
  `WorkflowContext.callActivityByName(name, input)` under the **same** name computed from `Impl`. The macro
  **verifies** each `Calls` method against `Impl` (matching Scala name; input type must match — output
  agreement is enforced by the generated body's return type), keeping the two sides bound although they are
  separate declarations.

## Core changes that made it possible

* `WorkflowActivity.activityName: String` — overridable, default `getClass.getCanonicalName`. Existing
  class-based activities and `callActivity[A]` are unaffected (same default); derived anonymous activities
  override it with the computed string. `DaprAppServer` now registers under `a.activityName`.
* `WorkflowContext.callActivityByName[I, O](name: ActivityName, input)` and the no-input overload (plus
  companion forwarders + `WorkflowContextImpl`). A **distinct name** (not an overload of `callActivity`) on
  purpose — overloading `callActivity` with a name-based variant made `callActivity[A](input)` ambiguous.
* New opaque type `ActivityName`.

## Usage

```scala
// implementation — a plain class, no WorkflowActivity boilerplate
class CounterActivities:
  def add(input: IncrRequest)(using DaprCapability): CounterState = CounterState(input.amount * 2)

// typed caller — a small trait bound to the impl by the macro
trait CounterCalls:
  def add(input: IncrRequest)(using ctx: WorkflowContext): Task[CounterState]^{ctx}

class AddingWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    // derive here: JsonCodec[I]/[O] are summoned at the derive site, in scope inside the workflow
    val acts = WorkflowActivityCalls.derive[CounterCalls, CounterActivities]
    WorkflowContext.complete(acts.add(WorkflowContext.getInput[IncrRequest].get).await())

object MyApp:
  def apply() = DaprApp(workflows = List(new AddingWorkflow), activities = WorkflowActivities.derive[CounterActivities])
```

Proven by `WorkflowActivityDerivationTest` (Docker-free, recording fakes) and the refactored
`test/integration/apps/WorkflowApp.scala` (compiles under safe mode; exercised by the real-sidecar
`WorkflowCapabilityServerTest`).
