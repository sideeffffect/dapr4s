package dapr4s.invoke

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Capability for synchronous service invocation (RPC) via DAPR.
  *
  * '''Dual:''' [[InvokeRoute]] is the inbound counterpart — a call this capability makes to an [[InvokeMethodName]] is
  * answered by an `InvokeRoute` for the same method on the target app. (Derivation binds the two through one trait:
  * `Invoke.derive` ↔ `InvokeRoutes.deriveChecked`.)
  */
/** Accessor (rung 2) for service invocation: an "any app" handle obtained argument-less via [[DaprCapability.invoke]],
  * whose [[apply]] narrows to an [[InvokeCapability]] bound to one target [[AppId]]. This is the service-to-service
  * least-privilege seam — hold `AccessInvokeCapability` to reach any app, or `apply(appId)` to restrict a holder to a
  * single target.
  */
@scala.caps.assumeSafe
trait AccessInvokeCapability extends scala.caps.ExclusiveCapability:
  /** Obtain an [[InvokeCapability]] bound to the target app. */
  def apply(appId: AppId): InvokeCapability^{this}

@scala.caps.assumeSafe
trait InvokeCapability extends scala.caps.ExclusiveCapability:
  val appId: AppId

  /** Invoke a remote method with a request body. `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{invoke.invoke(method, requestData)[ResponseType]}}}
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

/** Companion-object API for [[InvokeCapability]].
  *
  * Forwards to the `InvokeCapability` in the enclosing `using` context (already bound to a target app):
  * {{{
  *   def getUser(id: String)(using InvokeCapability): User =
  *     InvokeCapability.invoke(InvokeMethodName("get"), id)[User]
  * }}}
  */
@scala.caps.assumeSafe
object InvokeCapability:
  def invoke[Req: JsonCodec](
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: InvokeCapability): Resp =
    cap.invoke(method, data, httpMethod, metadata)[Resp]
  def invoke[Resp: JsonCodec](method: InvokeMethodName)(using cap: InvokeCapability): Resp =
    cap.invoke(method)

