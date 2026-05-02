package dapr.safe.test.integration

import dapr.safe.*
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import scala.collection.mutable
import java.util.{HashMap as JHashMap}

/** An [[AppHandlers]] implementation for integration tests.
  *
  * Instead of starting an HTTP server, this implementation captures registered
  * handlers in memory so tests can invoke them directly — without requiring a
  * real Dapr sidecar to push HTTP callbacks.
  *
  * Handlers are wired against a real [[DaprScope]] (backed by a Testcontainers
  * Dapr sidecar), so all state and pub/sub operations inside the handlers use
  * the actual Dapr API.
  *
  * {{{
  *   val handlers = TestAppHandlers()
  *   DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
  *     MyServiceHandlers.configure()(using summon[DaprScope], handlers)
  *
  *     // Invoke a registered method directly
  *     val resp = handlers.call[MyRequest]("my-method", MyRequest(...))[MyResponse]
  *
  *     // Deliver a pub/sub event directly to a registered subscriber
  *     handlers.deliver("my-topic", cloudEvent)
  * }}}
  *
  * WHY @assumeSafe + AnyRef storage:
  * The AppHandlers trait uses `{cap}` on handler parameter types, which allows
  * callers to pass capturing lambdas freely. Internally we must store those
  * lambdas for later dispatch. We use Java HashMap[String, AnyRef] to erase the
  * capture set completely — the CC type system cannot track what's inside AnyRef.
  * The @assumeSafe annotation lets us write the cast-and-call pattern without CC
  * errors. This is the standard boundary pattern: the messy internal bookkeeping
  * is contained here so callers get a clean, CC-checked API.
  */
@scala.caps.assumeSafe
final class TestAppHandlers extends AppHandlers:

  // Invoke routes: method name -> (requestJson -> responseJson), stored as AnyRef
  private val invokeRoutes: JHashMap[String, AnyRef] = JHashMap()
  // Subscribe routes: topic name -> (CloudEvent[Any] -> SubscriptionResult), stored as AnyRef
  private val subscribeRoutes: JHashMap[String, AnyRef] = JHashMap()

  // -------------------------------------------------------------------------
  // AppHandlers implementation
  // -------------------------------------------------------------------------

  override def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit =
    val fn: CloudEvent[?] => SubscriptionResult =
      ev => handler(ev.asInstanceOf[CloudEvent[T]])
    subscribeRoutes.put(topic.value, fn.asInstanceOf[AnyRef])

  override def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic, route: Route)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit =
    subscribe(pubsubName, topic)(handler)

  override def onBinding[T: JsonCodec](bindingName: BindingName)(handler: T => Unit): Unit = ()

  override def onInvoke[Req: JsonCodec](methodName: MethodName)[Resp: JsonCodec](
    handler: Req => Resp
  ): Unit =
    val reqCodec  = summon[JsonCodec[Req]]
    val respCodec = summon[JsonCodec[Resp]]
    val fn: String => String = reqJson =>
      val req = reqCodec.decode(reqJson) match
        case Right(v)  => v
        case Left(err) => throw err
      respCodec.encode(handler(req))
    invokeRoutes.put(methodName.value, fn.asInstanceOf[AnyRef])

  override def registerWorkflow(workflow: DaprWorkflow): Unit  = ()
  override def registerActivity(activity: DaprActivity): Unit = ()

  // -------------------------------------------------------------------------
  // Test helpers
  // -------------------------------------------------------------------------

  /** Invoke a registered method handler directly, encoding `request` and
    * decoding the response.  Fails fast if no handler is registered under
    * `method`.
    */
  def call[Req: JsonCodec](method: String, request: Req)[Resp: JsonCodec]: Resp =
    val fn = invokeRoutes.get(method)
    if fn == null then throw new java.util.NoSuchElementException(
      s"TestAppHandlers: no invoke handler for '$method'"
    )
    val reqJson  = summon[JsonCodec[Req]].encode(request)
    val respJson = fn.asInstanceOf[String => String](reqJson)
    summon[JsonCodec[Resp]].decode(respJson) match
      case Right(v)  => v
      case Left(err) => throw err

  /** Deliver a pub/sub event directly to a registered subscriber.
    * Fails fast if no subscriber is registered for `topic`.
    */
  def deliver[T](topic: String, event: CloudEvent[T]): SubscriptionResult =
    val fn = subscribeRoutes.get(topic)
    if fn == null then throw new java.util.NoSuchElementException(
      s"TestAppHandlers: no subscriber for topic '$topic'"
    )
    fn.asInstanceOf[CloudEvent[?] => SubscriptionResult](event)

  /** Returns `true` if a method handler is registered under `method`. */
  def hasMethod(method: String): Boolean = invokeRoutes.containsKey(method)

  /** Returns `true` if a subscriber is registered for `topic`. */
  def hasSubscriber(topic: String): Boolean = subscribeRoutes.containsKey(topic)
