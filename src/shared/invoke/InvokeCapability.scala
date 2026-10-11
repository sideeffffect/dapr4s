package dapr4s.invoke

import dapr4s.*

/** Universal capability for synchronous service invocation (RPC) via DAPR: reaches **any** app, so every operation
  * names its target [[AppId]].
  *
  * Obtained via [[DaprCapability.invoke]]. Narrow to one target app with [[apply]] (`invoke(appId)`), which yields an
  * [[InvokeCapability.Instance]]. This is the service-to-service least-privilege seam — hold `InvokeCapability` to
  * reach any app, or an `InvokeCapability.Instance` to restrict a holder to a single target.
  *
  * '''Dual:''' [[InvokeRoute]] is the inbound counterpart — a call this capability makes to an [[InvokeMethodName]] is
  * answered by an `InvokeRoute` for the same method on the target app. (Derivation binds the two through one trait:
  * `Invoke.derive` ↔ `InvokeRoutes.deriveChecked`.)
  */
@scala.caps.assumeSafe
trait InvokeCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the target app. */
  def instance(appId: AppId): InvokeCapability.Instance^{this}

  /** Invoke a remote method on `appId` with a request body; see [[InvokeCapability.Instance.invoke]]. */
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp

  /** Invoke a remote method on `appId` with no request body (GET, no metadata). */
  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName): Resp

/** The [[InvokeCapability]] family: the app-bound [[InvokeCapability.Instance]] and the companion-object forwarders for
  * both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def getUser(id: String)(using InvokeCapability.Instance): User =
  *     InvokeCapability.Instance.invoke(InvokeMethodName("get"), id)[User]
  * }}}
  */
@scala.caps.assumeSafe
object InvokeCapability:

  /** Capability bound to one target app. Obtained via `invoke(appId)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The target app this capability is bound to. */
    val appId: AppId

    /** Invoke a remote method with a request body. `Req` is inferred from `data`; `Resp` is specified at the call
      * site: {{{invoke.invoke(method, requestData)[ResponseType]}}}
      *
      * @param httpMethod
      *   HTTP verb to use; defaults to [[HttpMethod.Post]]
      * @param metadata
      *   optional gRPC/HTTP metadata headers forwarded to the target service
      */
    def invoke[Req: JsonCodec](
        method: InvokeMethodName,
        data: Req,
        httpMethod: HttpMethod = HttpMethod.Post,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )[Resp: JsonCodec]: Resp

    /** Invoke a remote method with no request body (GET, no metadata).
      *
      * Use the body-bearing overload to pass a non-default HTTP verb or metadata headers.
      */
    def invoke[Resp: JsonCodec](method: InvokeMethodName): Resp

  /** Forwarders for [[InvokeCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def invoke[Req: JsonCodec](
        method: InvokeMethodName,
        data: Req,
        httpMethod: HttpMethod = HttpMethod.Post,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )[Resp: JsonCodec](using cap: Instance): Resp =
      cap.invoke(method, data, httpMethod, metadata)[Resp]
    def invoke[Resp: JsonCodec](method: InvokeMethodName)(using cap: Instance): Resp =
      cap.invoke(method)

  /** Narrow the universal capability in scope to one target app and return it. */
  def instance(appId: AppId)(using cap: InvokeCapability): Instance^{cap} =
    cap.instance(appId)
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: InvokeCapability): Resp =
    cap.invoke(appId, method, data, httpMethod, metadata)[Resp]
  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName)(using cap: InvokeCapability): Resp =
    cap.invoke(appId, method)
