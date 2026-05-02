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
  * === Capability capture in handlers ===
  *
  * Handler lambdas commonly capture `DaprScope` capabilities (state, pub/sub,
  * etc.) from the enclosing `serve` or `configure` scope.  This is supported
  * without any `asInstanceOf` cast — in Scala 3.9 CC, function types with the
  * `pureFunctions` flag accept capturing lambdas as long as the CanThrow
  * capability is contained per lambda.  The required pattern is a `try/catch`
  * around each handler body:
  *
  * {{{
  *   handlers.onInvoke[OrderRequest](MethodName("place-order"))[OrderResponse] { req =>
  *     try placeOrder(req)           // placeOrder declares throws Exception
  *     catch case e: Exception => throw e  // absorbs CanThrow at this lambda boundary
  *   }
  * }}}
  *
  * See AGENTS.md (CC sibling lambda pattern) for the full explanation.
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
  def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic, route: Route)(
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
  def onInvoke[Req: JsonCodec](methodName: MethodName)[Resp: JsonCodec](
    handler: Req => Resp
  ): Unit

  /** Register a workflow orchestration implementation with the Dapr workflow runtime.
    *
    * The workflow is identified by its canonical class name, which clients must
    * pass to [[WorkflowCapability.start]].
    *
    * @example {{{
    *   handlers.registerWorkflow(new OrderWorkflow())
    *   // start via: workflow.start(WorkflowName(classOf[OrderWorkflow].getCanonicalName.nn))
    * }}}
    */
  def registerWorkflow(workflow: DaprWorkflow): Unit

  /** Register an activity implementation with the Dapr workflow runtime.
    *
    * Activities are called from orchestrations via `ctx.callActivity(classOf[MyActivity], ...)`.
    */
  def registerActivity(activity: DaprActivity): Unit
