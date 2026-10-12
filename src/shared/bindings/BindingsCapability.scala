package dapr4s.bindings

import dapr4s.*

/** Universal capability for invoking DAPR output bindings: reaches **any** binding, so every operation names its
  * binding.
  *
  * Obtained via [[DaprCapability.bindings]]. Narrow to one binding with [[instance]] (`bindings.instance(bindingName)`), which
  * yields a [[BindingsCapability.Instance]].
  *
  * '''Dual:''' [[BindingRoute]] is the inbound counterpart (input bindings). Note these are independent directions,
  * not a request/response contract: this capability issues [[BindingOperation]]s on a binding, while a `BindingRoute`
  * merely receives payloads delivered to a [[BindingName]]. (So `BindingRoutes` has only `derive`, no `deriveChecked`.)
  */
@scala.caps.assumeSafe
trait BindingsCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the named output binding. */
  def instance(bindingName: BindingName): BindingsCapability.Instance^{this}

  /** Invoke a binding operation on the named binding; see [[BindingsCapability.Instance.invoke]]. */
  def invoke[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp]

  /** Fire-and-forget invocation of the named binding; see [[BindingsCapability.Instance.invokeOneWay]]. */
  def invokeOneWay[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit

/** The [[BindingsCapability]] family: the binding-bound [[BindingsCapability.Instance]] and the companion-object
  * forwarders for both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def sendEmail(msg: EmailRequest)(using BindingsCapability.Instance): Unit =
  *     BindingsCapability.Instance.invokeOneWay(BindingOperation("create"), msg)
  * }}}
  */
@scala.caps.assumeSafe
object BindingsCapability:

  /** Capability bound to one named output binding. Obtained via `bindings.instance(bindingName)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The output binding this capability is bound to. */
    val bindingName: BindingName

    /** Invoke a binding operation that may return a response. `Req` is inferred from `data`; `Resp` is specified at
      * the call site: {{{binding.invoke(operation, requestData)[ResponseType]}}}
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

  /** Forwarders for [[BindingsCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def invoke[Req: JsonCodec](
        operation: BindingOperation,
        data: Req,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )[Resp: JsonCodec](using cap: Instance): Option[Resp] =
      cap.invoke(operation, data, metadata)[Resp]
    def invokeOneWay[Req: JsonCodec](
        operation: BindingOperation,
        data: Req,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )(using cap: Instance): Unit =
      cap.invokeOneWay(operation, data, metadata)

  /** Narrow the universal capability in scope to one binding and return it. */
  def instance(bindingName: BindingName)(using cap: BindingsCapability): Instance^{cap} =
    cap.instance(bindingName)
  def invoke[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: BindingsCapability): Option[Resp] =
    cap.invoke(bindingName, operation, data, metadata)[Resp]
  def invokeOneWay[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: BindingsCapability): Unit =
    cap.invokeOneWay(bindingName, operation, data, metadata)
