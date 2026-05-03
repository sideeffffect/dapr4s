package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.unit.MockActorContext
import unsafeExceptions.canThrowAny

/** Test helper for exercising [[DaprApp]] handler logic in-process.
  *
  * Instead of starting an HTTP server, the `call`, `deliver`, and `callActor` methods dispatch directly to the
  * registered handlers captured in a [[DaprApp]] — allowing integration tests to invoke business logic against a real
  * Dapr sidecar without any HTTP round-trips.
  *
  * {{{
  *   DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
  *     val scope = summon[DaprCapability]
  *     val app   = MyServiceHandlers.daprApp(using scope)
  *
  *     val resp    = TestDaprApp.call[MyRequest](app, "my-method", MyRequest(...))[MyResponse]
  *     val res     = TestDaprApp.deliver(app, "my-topic", cloudEvent)
  *     val actorCtx = new MockActorContext
  *     val actorResp = TestDaprApp.callActor[IncrReq](app, "Counter", "1", "increment", IncrReq(5), actorCtx)[Int]
  * }}}
  *
  * WHY @assumeSafe: the handler lambdas stored in [[DaprApp]] capture DAPR capabilities (state, pub/sub, etc.) from the
  * scope that created the app. Calling those lambdas and threading through the codec encode/decode round-trip that
  * bridges existential type members to the caller's concrete types both require bypassing CC tracking — the standard
  * library-boundary pattern.
  */
@scala.caps.assumeSafe
object TestDaprApp:

  /** Invoke a registered method handler directly, encoding `request` with the caller's codec and decoding the response
    * back to `Resp`.
    *
    * The encode → decode round-trip bridges the caller's concrete `Req`/`Resp` types to the existential
    * `inv.Req`/`inv.Resp` types without `asInstanceOf`. Fails fast if no handler is registered for `method`.
    */
  def call[Req: JsonCodec](app: DaprApp, method: String, request: Req)[Resp: JsonCodec]: Resp =
    val inv = app.invocations
      .find(_.methodName.value == method)
      .getOrElse(
        throw java.util.NoSuchElementException(s"TestDaprApp: no invocation route for '$method'"),
      )
    val handler = inv.rawHandler.asInstanceOf[inv.Req => inv.Resp]
    val reqJson = summon[JsonCodec[Req]].encode(request)
    val req: inv.Req = inv.reqCodec.decode(reqJson) match
      case Right(v) => v
      case Left(e)  => throw e
    val resp: inv.Resp = handler(req)
    val respJson = inv.respCodec.encode(resp)
    summon[JsonCodec[Resp]].decode(respJson) match
      case Right(v) => v
      case Left(e)  => throw e

  /** Deliver a pub/sub event directly to a registered subscriber.
    *
    * The encode → decode round-trip bridges the caller's concrete `T` to the existential `sub.Payload` type;
    * `sub.codec` and `sub.rawHandler` are then used in a type-coherent way via path-dependent types on `sub`. Fails
    * fast if no subscriber is registered for `topic`.
    */
  def deliver[T: JsonCodec](app: DaprApp, topic: String, event: CloudEvent[T]): SubscriptionResult =
    val sub = app.subscriptions
      .find(_.topic.value == topic)
      .getOrElse(
        throw java.util.NoSuchElementException(s"TestDaprApp: no subscription for topic '$topic'"),
      )
    val handler = sub.rawHandler.asInstanceOf[CloudEvent[sub.Payload] => SubscriptionResult]
    val dataJson = summon[JsonCodec[T]].encode(event.data)
    val data: sub.Payload = sub.codec.decode(dataJson) match
      case Right(v) => v
      case Left(e)  => throw e
    handler(
      CloudEvent[sub.Payload](
        id = event.id,
        source = event.source,
        specVersion = event.specVersion,
        eventType = event.eventType,
        topic = event.topic,
        pubSubName = event.pubSubName,
        dataContentType = event.dataContentType,
        data = data,
      ),
    )

  /** Invoke an actor method directly in-process.
    *
    * Finds the [[ActorDefinition]] for `actorType`, calls `build(actorId, ctx)` to get the route table, then
    * dispatches to the matching [[ActorMethodRoute]] via the JSON codec round-trip. Pass a [[MockActorContext]] to
    * capture state changes and reminder/timer registrations for assertion.
    */
  def callActor[Req: JsonCodec](
      app: DaprApp,
      actorType: String,
      actorId: String,
      method: String,
      request: Req,
      ctx: ActorContext,
  )[Resp: JsonCodec]: Resp =
    val defn = app.actors
      .find(_.actorType.value == actorType)
      .getOrElse(
        throw java.util.NoSuchElementException(s"TestDaprApp: no actor definition for '$actorType'"),
      )
    val routes = defn.build(ActorId(actorId), ctx)
    val route  = routes.methods
      .find(_.methodName.value == method)
      .getOrElse(
        throw java.util.NoSuchElementException(
          s"TestDaprApp: no method '$method' on actor '$actorType'",
        ),
      )
    val handler  = route.rawHandler.asInstanceOf[route.Req => route.Resp]
    val reqJson  = summon[JsonCodec[Req]].encode(request)
    val req: route.Req = route.reqCodec.decode(reqJson) match
      case Right(v) => v
      case Left(e)  => throw e
    val resp: route.Resp = handler(req)
    val respJson = route.respCodec.encode(resp)
    summon[JsonCodec[Resp]].decode(respJson) match
      case Right(v) => v
      case Left(e)  => throw e

  /** Fire an actor reminder callback directly in-process.
    *
    * Finds the [[ActorReminderRoute]] matching `reminderName` and calls its handler with `data`.
    */
  def deliverReminder[T: JsonCodec](
      app: DaprApp,
      actorType: String,
      actorId: String,
      reminderName: String,
      data: T,
      ctx: ActorContext,
  ): Unit =
    val defn = app.actors
      .find(_.actorType.value == actorType)
      .getOrElse(
        throw java.util.NoSuchElementException(s"TestDaprApp: no actor definition for '$actorType'"),
      )
    val routes = defn.build(ActorId(actorId), ctx)
    val route  = routes.reminders
      .find(_.reminderName.value == reminderName)
      .getOrElse(
        throw java.util.NoSuchElementException(
          s"TestDaprApp: no reminder '$reminderName' on actor '$actorType'",
        ),
      )
    val handler  = route.rawHandler.asInstanceOf[route.Payload => Unit]
    val dataJson = summon[JsonCodec[T]].encode(data)
    val payload: route.Payload = route.codec.decode(dataJson) match
      case Right(v) => v
      case Left(e)  => throw e
    handler(payload)

  /** Fire an actor timer callback directly in-process.
    *
    * Finds the [[ActorTimerRoute]] matching `timerName` and calls its handler with `data`.
    */
  def deliverTimer[T: JsonCodec](
      app: DaprApp,
      actorType: String,
      actorId: String,
      timerName: String,
      data: T,
      ctx: ActorContext,
  ): Unit =
    val defn = app.actors
      .find(_.actorType.value == actorType)
      .getOrElse(
        throw java.util.NoSuchElementException(s"TestDaprApp: no actor definition for '$actorType'"),
      )
    val routes = defn.build(ActorId(actorId), ctx)
    val route  = routes.timers
      .find(_.timerName.value == timerName)
      .getOrElse(
        throw java.util.NoSuchElementException(
          s"TestDaprApp: no timer '$timerName' on actor '$actorType'",
        ),
      )
    val handler  = route.rawHandler.asInstanceOf[route.Payload => Unit]
    val dataJson = summon[JsonCodec[T]].encode(data)
    val payload: route.Payload = route.codec.decode(dataJson) match
      case Right(v) => v
      case Left(e)  => throw e
    handler(payload)
