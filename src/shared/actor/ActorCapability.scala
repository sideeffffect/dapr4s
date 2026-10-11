package dapr4s.actor

import dapr4s.*

/** Universal capability for invoking Dapr virtual actors: reaches **any** actor, so every operation names its
  * [[ActorType]] and [[ActorId]].
  *
  * Obtained via [[DaprCapability.actor]]. The actor family is the only one that narrows in '''two''' steps — type,
  * then id — so `actor.forType(actorType).instance(actorId)` reads as two narrowing steps. Its three interfaces all live in this
  * companion:
  *
  *   - [[ActorCapability]] — any actor of any type (this trait)
  *   - [[ActorCapability.Type]] — any instance of one actor type, via [[forType]]
  *   - [[ActorCapability.Instance]] — one actor instance, via [[ActorCapability.Type.instance]]
  *
  * '''Dual:''' [[ActorDefinition]] is the server counterpart — a call this capability makes to an [[ActorMethodName]]
  * is served by the matching method route of the actor's `ActorDefinition`. (Derivation binds the two through one
  * trait: `Actor.derive` ↔ `ActorDefinitions.deriveChecked`. The actor's reminders/timers are a separate sub-dualism,
  * scheduled via [[ActorContext.registerReminder]]/[[ActorContext.registerTimer]] rather than this capability.)
  */
@scala.caps.assumeSafe
trait ActorCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to a single actor type (any instance of it). */
  def forType(actorType: ActorType): ActorCapability.Type^{this}

  /** Invoke a method with a request body on the named actor; see [[ActorCapability.Instance.invoke]]. */
  def invoke[Req: JsonCodec](
      actorType: ActorType,
      actorId: ActorId,
      method: ActorMethodName,
      data: Req,
  )[Resp: JsonCodec]: Resp

  /** Invoke a method with no request body on the named actor. */
  def invoke[Resp: JsonCodec](actorType: ActorType, actorId: ActorId, method: ActorMethodName): Resp

  /** Invoke a method that returns no value on the named actor. */
  def invokeVoid(actorType: ActorType, actorId: ActorId, method: ActorMethodName): Unit

/** The [[ActorCapability]] family: the type-bound [[ActorCapability.Type]], the instance-bound
  * [[ActorCapability.Instance]], and the companion-object forwarders for all three interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def getBalance(id: ActorId)(using ActorCapability.Instance): Balance =
  *     ActorCapability.Instance.invoke(ActorMethodName("GetBalance"), BalanceRequest(id))[Balance]
  * }}}
  */
@scala.caps.assumeSafe
object ActorCapability:

  /** Capability bound to one actor type: reaches any instance of it, so every operation names its [[ActorId]].
    * Obtained via `actor.forType(actorType)`; narrow further with [[instance]].
    */
  @scala.caps.assumeSafe
  trait Type extends scala.caps.ExclusiveCapability:
    /** The actor type this capability is bound to. */
    val actorType: ActorType

    /** Narrow to a single actor instance of this type. */
    def instance(actorId: ActorId): Instance^{this}

    /** Invoke a method with a request body on the identified instance; see [[ActorCapability.Instance.invoke]]. */
    def invoke[Req: JsonCodec](actorId: ActorId, method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp

    /** Invoke a method with no request body on the identified instance. */
    def invoke[Resp: JsonCodec](actorId: ActorId, method: ActorMethodName): Resp

    /** Invoke a method that returns no value on the identified instance. */
    def invokeVoid(actorId: ActorId, method: ActorMethodName): Unit

  /** Forwarders for [[ActorCapability.Type]]. */
  @scala.caps.assumeSafe
  object Type:
    /** Narrow the type-bound capability in scope to one instance and return it. */
    def instance(actorId: ActorId)(using cap: Type): Instance^{cap} =
      cap.instance(actorId)
    def invoke[Req: JsonCodec](actorId: ActorId, method: ActorMethodName, data: Req)[Resp: JsonCodec](using
        cap: Type,
    ): Resp =
      cap.invoke(actorId, method, data)[Resp]
    def invoke[Resp: JsonCodec](actorId: ActorId, method: ActorMethodName)(using cap: Type): Resp =
      cap.invoke(actorId, method)
    def invokeVoid(actorId: ActorId, method: ActorMethodName)(using cap: Type): Unit =
      cap.invokeVoid(actorId, method)

  /** Capability bound to one actor instance (type + id). Obtained via `actor.forType(actorType).instance(actorId)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The actor type of the bound instance. */
    val actorType: ActorType

    /** The id of the bound instance. */
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

  /** Forwarders for [[ActorCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def invoke[Req: JsonCodec](method: ActorMethodName, data: Req)[Resp: JsonCodec](using cap: Instance): Resp =
      cap.invoke(method, data)[Resp]
    def invoke[Resp: JsonCodec](method: ActorMethodName)(using cap: Instance): Resp =
      cap.invoke(method)
    def invokeVoid(method: ActorMethodName)(using cap: Instance): Unit =
      cap.invokeVoid(method)

  /** Narrow the universal capability in scope to one actor type and return it as an
    * [[ActorCapability.Type]].
    */
  def forType(actorType: ActorType)(using cap: ActorCapability): Type^{cap} =
    cap.forType(actorType)

  /** Narrow the universal capability in scope to one actor instance and return it. */
  def instance(actorType: ActorType, actorId: ActorId)(using cap: ActorCapability): Instance^{cap} =
    cap.forType(actorType).instance(actorId)
  def invoke[Req: JsonCodec](actorType: ActorType, actorId: ActorId, method: ActorMethodName, data: Req)[
      Resp: JsonCodec,
  ](using cap: ActorCapability): Resp =
    cap.invoke(actorType, actorId, method, data)[Resp]
  def invoke[Resp: JsonCodec](actorType: ActorType, actorId: ActorId, method: ActorMethodName)(using
      cap: ActorCapability,
  ): Resp =
    cap.invoke(actorType, actorId, method)
  def invokeVoid(actorType: ActorType, actorId: ActorId, method: ActorMethodName)(using cap: ActorCapability): Unit =
    cap.invokeVoid(actorType, actorId, method)
