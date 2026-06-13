# dapr4s — `DaprApp` validation

## Goal

Catch *silent misconfiguration* in a `DaprApp` before it can cause wrong-handler
dispatch at runtime. The dispatch layer (`internal/DaprAppServer`) builds its
routing tables with `java.util.HashMap.put` and the Dapr runtime's
`registerWorkflow` / `registerActivity`, all of which are **last-write-wins**: a
second registration under an existing key silently overwrites the first. Nothing
today tells the user that two activities, two workflows, or two HTTP paths
collided — the app just dispatches to the wrong handler.

Validation makes those collisions a **hard, fail-fast error** at startup instead
of a latent production bug.

## Scope

In scope (v1):

- Duplicate **activity** names and duplicate **workflow** names.
- Within-category duplicates: subscription routes, invocation method names,
  binding names, job names, actor types.
- Cross-type **root-route collisions**, including collisions with framework
  **reserved paths**.
- Actor-internal duplicates (method / timer / reminder names), checked at actor
  **build time** rather than at `DaprApp` construction.

Explicitly **out of scope** (documented as follow-ups, see end):

- Activity *reachability* — verifying that every activity a workflow schedules is
  registered. This is not soundly determinable from instances (see
  [Follow-ups](#follow-ups)).
- Severity tiers. All findings are **errors**; there are no warnings.

## Design decisions

These were settled during the design interview:

| Decision | Choice |
| --- | --- |
| When validation runs | **Automatically inside `Dapr.serve`, fail-fast** before the HTTP port is bound. A public method is also exposed for manual/test use. |
| Severity model | **Errors only.** Any finding is fatal. |
| Check categories | Duplicate activity/workflow names; within-category dupes; cross-type root-route collisions **including reserved paths**; actor-internal dupes. |
| Reserved-path collisions | **Included.** `/dapr/subscribe`, `/dapr/config`, the `/actors` prefix, and the `/job/` prefix are reserved. |
| Actor-internal check timing | Validate the `ActorRoutes` returned by **every** `ActorDefinition.build`; throw immediately on a duplicate. Correct even when the build lambda returns different routes per `ActorId`; cost is a cheap list scan per invocation. |
| Reachability | **Left out**, documented as a follow-up. |

## Why each check matters

All references below are to the current `src/` tree.

### Duplicate activity names — `internal/DaprAppServer.scala:136-141`

Activities are registered with `wb.registerActivity(a.activityName, …)`. The wire
name is `WorkflowActivity.activityName` (`Workflows.scala:264`), which defaults to
the simple class name (uniform with workflows); derived activities use `<impl-full-name>#<method>`
(`derivation/MacroSupport.scala:84`). Two activities resolving to the same name →
the second silently replaces the first. A workflow scheduling that name (via
`callActivity[A]` or `callActivityByName`) may then hit the wrong implementation.

### Duplicate workflow names — `internal/DaprAppServer.scala:133-135`

Workflows are registered under **`getClass.getSimpleName`**, *not* the canonical
name. Two `Workflow` classes with the same simple name in different packages
collide silently — invisible in source review. The duplicate check must mirror the
real registration key (`getSimpleName`) so it detects exactly what would collide.

### Within-category duplicates — `internal/DaprAppServer.scala:49-119`

`pubSubRoutes`, `bindingRoutes`, `invokeRoutes`, `jobRoutes`, and `actorDefs` are
all `HashMap`s populated by `put`. Two subscriptions on the same route, two
invokeRoutes with the same method name, two bindings/jobs/actor-types with the same
name → last wins.

### Cross-type root-route collisions — `internal/DaprAppServer.scala:236-287`

Pub/sub delivery, input bindings, and service invocation all share the **single
`/` catch-all** context, dispatched in a *fixed lookup order*:

```
pub/sub route  →  binding name  →  invocation method  →  job
```

So a binding named `foo` and an invocation method `foo` both map to path `/foo`;
the binding always wins and the invocation is unreachable — no error, no log.
The effective path for each handler:

| Handler | Effective path |
| --- | --- |
| Subscription | `route.value` (normalised to a leading `/`); default `/<topic>` |
| Binding | `/<bindingName>` |
| Invocation | `/<methodName>` |
| Job | `/job/<jobName>` |

Jobs sit under the `/job/` prefix so they do not collide with the root namespace
(except via a contrived subscription route literally starting `/job/`).

### Reserved-path collisions — `internal/DaprAppServer.scala:155,175,222,236`

`HttpServer` uses longest-prefix matching, and the framework registers explicit
contexts that win over the `/` catch-all:

- `/dapr/subscribe` (exact)
- `/dapr/config` (exact)
- `/actors` (prefix — shadows any path under `/actors/...`)
- `/job/` (prefix — owned by job dispatch)

A user handler whose effective path equals `/dapr/subscribe` or `/dapr/config`,
or falls under `/actors` or `/job/`, is silently shadowed by the framework
endpoint. These are flagged as collisions too.

### Actor-internal duplicates — `Actors.scala:266-273`, `internal/DaprAppServer.scala:376-444`

`ActorDefinition.build(id, ctx)` returns an `ActorRoutes(methods, reminders,
timers)` on **every** actor invocation. Dispatch then does
`routes.methods.find(_.methodName.value == name)` (and likewise for reminders /
timers) — `find` returns the *first* match, so a duplicate method name within one
actor silently shadows the later handler.

Because routes are produced per request and may legitimately differ per `ActorId`,
this check cannot run at `DaprApp` construction time. Instead it runs **inside
`ActorDefinition.build`**, validating the freshly produced `ActorRoutes` and
throwing immediately on a duplicate. This is the "check immediately upon
constructing an Actor" decision.

## Public API

```scala
package dapr4s

/** A single validation failure. */
enum DaprAppValidationError:
  case DuplicateActivityName(name: String, count: Int)
  case DuplicateWorkflowName(simpleName: String, count: Int)
  case DuplicateSubscriptionRoute(path: String, count: Int)
  case DuplicateInvocationMethod(name: String, count: Int)
  case DuplicateBindingName(name: String, count: Int)
  case DuplicateJobName(name: String, count: Int)
  case DuplicateActorType(actorType: String, count: Int)
  /** Two or more handlers of differing kinds map to the same effective HTTP path. */
  case RouteCollision(path: String, kinds: List[String])
  /** A user handler's effective path collides with a framework-reserved path. */
  case ReservedPathCollision(path: String, kind: String, reserved: String)

  /** Human-readable, single-line description for error aggregation. */
  def message: String

final case class DaprApp(...):

  /** All validation problems found, in deterministic order. Empty == valid.
    * Pure; performs no I/O and starts nothing. */
  def validationErrors: List[DaprAppValidationError]

  /** Throw `DaprAppValidationException` listing every problem, or return `this`
    * unchanged so it can be used inline:
    * `Dapr(cfg).serve { DaprApp(...).validateOrThrow() }`. */
  def validateOrThrow(): DaprApp
```

```scala
/** Thrown by `validateOrThrow` and by `Dapr.serve`'s startup check.
  * The message lists every error so a user fixes them all in one pass. */
final class DaprAppValidationException(val errors: List[DaprAppValidationError])
    extends IllegalStateException(...)
```

Notes:

- `validationErrors` aggregates **all** problems (does not stop at the first), so
  one run surfaces everything to fix.
- Actor-internal duplicates are *not* part of `validationErrors` (they are not
  statically available). They are enforced separately at build time and throw the
  same `DaprAppValidationException`.

### Integration into `Dapr.serve` — `Dapr.scala:180-191`

`serve` calls `app.validateOrThrow()` after evaluating `body` and **before**
constructing/starting `DaprAppServer`, so a misconfigured app fails fast at
startup without ever binding the port:

```scala
def serve(body: DaprCapability ?=> DaprApp): Nothing =
  run:
    val cap = summon[DaprCapability]
    val app = body.validateOrThrow()          // <-- fail-fast
    new internal.DaprAppServer(app).startAndBlock(...)
```

### Actor build hook — `Actors.scala:266-273`

`ActorDefinition.build` validates the `ActorRoutes` it produces and throws on a
duplicate method / reminder / timer name within that actor, on every invocation:

```scala
private[dapr4s] def build(id: ActorId, ctx: ActorContext): ActorRoutes =
  val routes = rawBuild.asInstanceOf[...](id)(ctx.asInstanceOf[AnyRef])
  ActorRoutesValidation.check(actorType, routes)   // throws on duplicates
  routes
```

## Algorithm

`validationErrors` is a pure fold over the `DaprApp` fields:

1. **Group-and-count** for each duplicate check, emitting one error per key whose
   count > 1:
   - activities by `activityName`
   - workflows by `getClass.getSimpleName`
   - subscriptions by normalised route path
   - invokeRoutes by method name
   - bindings by name; jobs by name; actors by `actorType`
2. **Root-route map**: build `path -> List[kind]` over subscriptions, bindings,
   and invokeRoutes (using the effective-path table above). Any path with more than
   one *distinct* kind → `RouteCollision`. (Same-kind duplicates are already
   covered by step 1, so they are not double-reported here.)
3. **Reserved-path scan**: for each subscription / binding / invocation effective
   path, emit `ReservedPathCollision` if it equals `/dapr/subscribe` or
   `/dapr/config`, or starts with `/actors` or `/job/`.

Output order is deterministic (category, then key) so tests can assert on it.

The actor-internal check (`ActorRoutesValidation.check`) is a separate
group-and-count over `routes.methods` / `reminders` / `timers`, throwing a
`DaprAppValidationException` if any name repeats.

## Testing

Unit tests (no sidecar needed) covering:

- Each duplicate category: positive (collision detected) + negative (clean app
  validates).
- Cross-package workflow simple-name collision is detected (the `getSimpleName`
  footgun).
- Root-route collision across kinds (binding vs invocation on the same path).
- Reserved-path collisions for each reserved path/prefix.
- `validateOrThrow` aggregates *all* errors into one exception message.
- `validationErrors` is empty for the example apps under `test/shared/apps`.
- Actor build with duplicate method names throws; clean actor builds succeed.
- `serve` rejects an invalid app before binding the port (can be asserted by
  constructing the server path with an invalid `DaprApp` and expecting the
  exception, without standing up a sidecar).

## Follow-ups (out of scope here)

1. **Activity reachability.** Verifying that every activity a workflow schedules
   is registered is not soundly determinable from `Workflow` instances: the
   references live as imperative `callActivity[A]` / `callActivityByName` calls
   inside `run` (and any helpers it delegates to), with no declarative manifest.
   The clean path is to make dependencies *declarative* by extending the existing
   `WorkflowActivityCalls.derive[Calls, Impl]` facade
   (`derivation/WorkflowActivityCalls.scala`) so a workflow's activity set becomes
   data that can be cross-checked against `DaprApp.activities`. A best-effort
   bytecode scan of `run` for literal names/types is possible but fragile and was
   rejected.

2. **Workflow simple-name vs. canonical-name inconsistency.** *(Resolved.)* The
   server registers workflows by `getSimpleName`; the `WorkflowName` docs and the
   `WorkflowCapability.start` example were aligned to match, and class-based
   activities were made to default to `getSimpleName` too, so the whole library
   now names workflows and activities uniformly by simple class name. The
   `DuplicateWorkflowName` / `DuplicateActivityName` checks above catch the
   simple-name collisions this convention can produce.
