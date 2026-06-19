package dapr4s.actor

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Capability for invoking methods on a specific Dapr virtual actor instance.
  *
  * '''Dual:''' [[ActorDefinition]] is the server counterpart — a call this capability makes to an [[ActorMethodName]]
  * is served by the matching method route of the actor's `ActorDefinition`. (Derivation binds the two through one
  * trait: `Actor.derive` ↔ `ActorDefinitions.deriveChecked`. The actor's reminders/timers are a separate sub-dualism,
  * scheduled via [[ActorContext.registerReminder]]/[[ActorContext.registerTimer]] rather than this capability.)
  */
/** Accessor (rung 2) for actors: an "any actor" handle obtained argument-less via [[DaprCapability.actor]], whose
  * [[apply]] descends to an [[ActorTypeCapability]] for one actor type. The actor accessor is the only one that
  * descends two rungs — type then id — so `dapr.actor(actorType)(actorId)` reads as two narrowing steps.
  */
@scala.caps.assumeSafe
trait AccessActorCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to a single actor type (any instance of it). */
  def apply(actorType: ActorType): ActorTypeCapability^{this}

/** Accessor (rung 2.5) for a single actor type: narrows to a concrete [[ActorCapability]] for one instance id. */
@scala.caps.assumeSafe
trait ActorTypeCapability extends scala.caps.ExclusiveCapability:
  val actorType: ActorType

  /** Narrow to a single actor instance of this type. */
  def apply(actorId: ActorId): ActorCapability^{this}

@scala.caps.assumeSafe
trait ActorCapability extends scala.caps.ExclusiveCapability:
  val actorType: ActorType
  val actorId: ActorId

  /** Invoke an actor method with a request body.
    *
    * {{{
    *   val resp = actor.invoke(ActorMethodName("GetBalance"), req)[BalanceResponse]
    * }}}
    */
  def invoke[Req: JsonCodec](method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp

  /** Invoke an actor method with no request body. */
  def invoke[Resp: JsonCodec](method: ActorMethodName): Resp

  /** Invoke an actor method that returns no value. */
  def invokeVoid(method: ActorMethodName): Unit

/** Companion-object API for [[ActorCapability]].
  *
  * Forwards to the `ActorCapability` in the enclosing `using` context:
  * {{{
  *   def getBalance(id: ActorId)(using cap: ActorCapability): Balance =
  *     ActorCapability.invoke(ActorMethodName("GetBalance"), BalanceRequest(id))[Balance]
  * }}}
  */
@scala.caps.assumeSafe
object ActorCapability:
  def invoke[Req: JsonCodec](method: ActorMethodName, data: Req)[Resp: JsonCodec](using
      cap: ActorCapability,
  ): Resp =
    cap.invoke(method, data)[Resp]
  def invoke[Resp: JsonCodec](method: ActorMethodName)(using cap: ActorCapability): Resp =
    cap.invoke(method)
  def invokeVoid(method: ActorMethodName)(using cap: ActorCapability): Unit =
    cap.invokeVoid(method)

