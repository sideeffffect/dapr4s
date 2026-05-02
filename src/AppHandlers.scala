package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

/** Factory for registering inbound DAPR handlers (pub/sub subscriptions,
  * input bindings, service-invocation targets).
  *
  * An `AppHandlers` instance is provided by [[DaprRuntime.serve]] alongside
  * the [[DaprScope]].  Handlers are registered during the setup body; the
  * HTTP server starts after the body returns.
  *
  * Handler lambdas may capture the `DaprScope` from the enclosing `serve`
  * body to make outbound DAPR calls (e.g. save state on receiving a message).
  */
@scala.caps.assumeSafe
trait AppHandlers:

  /** Subscribe to `topic` on `pubsubName`.
    *
    * The default HTTP route is `/<topic>`. The sidecar will POST CloudEvents
    * to that path.  The handler should return [[SubscriptionResult.Success]],
    * [[SubscriptionResult.Retry]], or [[SubscriptionResult.Drop]].
    */
  def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit

  /** Subscribe to `topic` on `pubsubName` with an explicit HTTP route path. */
  def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic, route: String)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit

  /** Register an input binding handler.
    *
    * The Dapr sidecar calls `POST /<bindingName>` on the app when an external
    * event arrives.  The payload is decoded with `T`'s [[JsonCodec]].
    */
  def onBinding[T: JsonCodec](bindingName: BindingName)(
    handler: T => Unit
  ): Unit

  /** Register a service invocation target method.
    *
    * The Dapr sidecar calls `POST /<methodName>` (or GET/PUT/DELETE) when
    * another app invokes this app.  Use clause interleaving:
    * {{{
    *   handlers.onInvoke[OrderRequest]("checkout")[OrderResponse] { req => ... }
    * }}}
    * `Req` is inferred from the handler argument; `Resp` is specified at the
    * call site.
    */
  def onInvoke[Req: JsonCodec](methodName: String)[Resp: JsonCodec](
    handler: Req => Resp
  ): Unit
