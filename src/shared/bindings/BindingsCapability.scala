package dapr4s.bindings

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Capability for invoking DAPR output bindings.
  *
  * '''Dual:''' [[BindingRoute]] is the inbound counterpart (input bindings). Note these are independent directions,
  * not a request/response contract: this capability issues [[BindingOperation]]s on a binding, while a `BindingRoute`
  * merely receives payloads delivered to a [[BindingName]]. (So `BindingRoutes` has only `derive`, no `deriveChecked`.)
  */
/** Accessor (rung 2) for output bindings: an "any binding" handle obtained argument-less via
  * [[DaprCapability.bindings]], whose [[apply]] narrows to a [[BindingsCapability]] bound to one binding.
  */
@scala.caps.assumeSafe
trait AccessBindingsCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[BindingsCapability]] for the named output binding. */
  def apply(bindingName: BindingName): BindingsCapability^{this}

@scala.caps.assumeSafe
trait BindingsCapability extends scala.caps.ExclusiveCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response. `Req` is inferred from `data`; `Resp` is specified at the
    * call site: {{{binding.invoke(operation, requestData)[ResponseType]}}}
    *
    * @param metadata
    *   optional metadata forwarded to the binding component
    */
  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp]

  /** Fire-and-forget binding invocation (no response expected).
    *
    * @param metadata
    *   optional metadata forwarded to the binding component
    */
  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit

/** Companion-object API for [[BindingsCapability]].
  *
  * Forwards to the `BindingsCapability` in the enclosing `using` context:
  * {{{
  *   def sendEmail(msg: EmailRequest)(using BindingsCapability): Unit =
  *     BindingsCapability.invokeOneWay(BindingOperation("create"), msg)
  * }}}
  */
@scala.caps.assumeSafe
object BindingsCapability:
  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: BindingsCapability): Option[Resp] =
    cap.invoke(operation, data, metadata)[Resp]
  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: BindingsCapability): Unit =
    cap.invokeOneWay(operation, data, metadata)

