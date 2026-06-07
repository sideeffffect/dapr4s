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
      ActorMethodRoute[Unit, Unit](ActorMethodName("schedule-quick-reset"))(scheduleQuickReset),
      ActorMethodRoute[Unit, Unit](ActorMethodName("schedule-auto-increment"))(scheduleAutoIncrement),
    ),
    reminders = List(
      ActorReminderRoute[String](ReminderName("scheduled-reset"))(msg => onScheduledReset(msg)),
    ),
    timers = List(
      ActorTimerRoute[IncrRequest](TimerName("auto-increment"))(req => onAutoIncrement(req)),
    ),
  )
}
```

Also, is there some opportunity to improve something for Workflows using this technique?
Maybe something like

```scala
trait MyWorkflowSchema {
  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
}

@WorkflowActivityDerivation
class MyWorkflowActivity extends MyWorkflowSchema {
  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState =
   CounterState(input.amount * 2)
}
```

would then turn into the dapr4s reified model

```scala
object MyWorkflowActivity extends MyWorkflowSchema {
  class AddActivity(JsonCodec[IncrRequest], JsonCodec[CounterState]) extends WorkflowActivity[IncrRequest, CounterState]:
    def execute(input: IncrRequest)(using DaprCapability): CounterState =
      CounterState(input.amount * 2)

  def addActivity(input: IncrRequest)(using DaprCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState =
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
