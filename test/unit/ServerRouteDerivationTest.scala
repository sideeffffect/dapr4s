package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite
import scala.collection.mutable

/** A non-capability "ambient" dependency, summoned into derived handler bodies. */
trait Recorder:
  def log: mutable.ListBuffer[String]

object InvHandlers:
  def echo(req: Req): Resp = Resp(s"echo${req.n}") // pure handler
  def store(req: Req)(using r: Recorder): Resp = // uses an ambient given
    r.log += s"store${req.n}"
    Resp("stored")
  @name("get-thing") def thing(): Resp = Resp("thing") // no input → Unit, @name

object SubHandlers:
  @name("orders") def onOrder(e: CloudEvent[Req])(using r: Recorder): SubscriptionResult =
    r.log += s"order${e.data.n}"
    SubscriptionResult.Success
  @name("audit") @deadLetter("audit-dlq") def onAudit(e: CloudEvent[Req]): SubscriptionResult =
    SubscriptionResult.Success

/** Plain server handler answering the [[Greeter]] caller contract — no capability, no knobs, free to take its own
  * ambient `using` dependency (`store`). Bound to the contract via `InvokeRoutes.derive[Greeter, GreeterImpl.type]`.
  */
object GreeterImpl:
  def double(req: Req): Resp = Resp(s"double${req.n}")
  def plain(req: Req)(using r: Recorder): Resp =
    r.log += s"plain${req.n}"
    Resp("plain")
  def stats(): Resp = Resp("stats") // contract's @name("get-stats") governs the wire name
  def echo(req: Resp): Resp = req

@scala.caps.assumeSafe
class ServerRouteDerivationTest extends FunSuite:

  private def ce(data: Req): CloudEvent[Req] =
    CloudEvent(
      CloudEventId("id"),
      CloudEventSource("src"),
      CloudEventSpecVersion("1.0"),
      CloudEventType("type"),
      Topic("orders"),
      PubSubName("pubsub"),
      ContentType("application/json"),
      data,
    )

  test("InvokeRoutes.derive: names, pure + capability handlers, @name, Unit input"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val routes = InvokeRoutes.derive[InvHandlers.type]
    assertEquals(routes.map(_.methodName.value).sorted, List("echo", "get-thing", "store"))

    val echo = routes.find(_.methodName.value == "echo").get
    assertEquals(echo.rawHandler.asInstanceOf[Any => Any](Req(5)), Resp("echo5"))
    val store = routes.find(_.methodName.value == "store").get
    assertEquals(store.rawHandler.asInstanceOf[Any => Any](Req(7)), Resp("stored"))
    val thing = routes.find(_.methodName.value == "get-thing").get
    assertEquals(thing.rawHandler.asInstanceOf[Any => Any](()), Resp("thing"))
    assertEquals(rec.log.toList, List("store7"))

  test("Subscriptions.derive: topics, @deadLetter, pubsubName, dispatch"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val subs = Subscriptions.derive[SubHandlers.type](PubSubName("pubsub"))
    assertEquals(subs.map(_.topic.value).sorted, List("audit", "orders"))

    val orders = subs.find(_.topic.value == "orders").get
    assertEquals(orders.pubsubName.value, "pubsub")
    assertEquals(orders.deadLetterTopic.map(_.value), None)
    val audit = subs.find(_.topic.value == "audit").get
    assertEquals(audit.deadLetterTopic.map(_.value), Some("audit-dlq"))

    orders.rawHandler.asInstanceOf[Any => Any](ce(Req(9)))
    assertEquals(rec.log.toList, List("order9"))

  test("InvokeRoutes.derive[Contract, Impl]: wire names from contract, dispatch to plain impl"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val routes = InvokeRoutes.derive[Greeter, GreeterImpl.type]
    // double, plain, echo map verbatim; stats answers the contract's @name("get-stats")
    assertEquals(routes.map(_.methodName.value).sorted, List("double", "echo", "get-stats", "plain"))

    val double = routes.find(_.methodName.value == "double").get
    assertEquals(double.rawHandler.asInstanceOf[Any => Any](Req(3)), Resp("double3"))
    val plain = routes.find(_.methodName.value == "plain").get
    assertEquals(plain.rawHandler.asInstanceOf[Any => Any](Req(4)), Resp("plain"))
    val stats = routes.find(_.methodName.value == "get-stats").get
    assertEquals(stats.rawHandler.asInstanceOf[Any => Any](()), Resp("stats"))
    val echo = routes.find(_.methodName.value == "echo").get
    assertEquals(echo.rawHandler.asInstanceOf[Any => Any](Resp("x")), Resp("x"))
    assertEquals(rec.log.toList, List("plain4"))

  test("Subscriptions.derive[Contract, Impl]: checks publisher contract by topic, dispatches"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    // Publisher (orders/audit topics, Req payload) checked against SubHandlers (same topics, CloudEvent[Req]).
    val subs = Subscriptions.derive[Publisher, SubHandlers.type](PubSubName("pubsub"))
    assertEquals(subs.map(_.topic.value).sorted, List("audit", "orders"))
    val orders = subs.find(_.topic.value == "orders").get
    orders.rawHandler.asInstanceOf[Any => Any](ce(Req(11)))
    assertEquals(rec.log.toList, List("order11"))

  test("Subscriptions.derive without pubsubName uses the handler type's simple name"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val subs = Subscriptions.derive[SubHandlers.type]
    assertEquals(subs.map(_.pubsubName.value).distinct, List("SubHandlers"))
    assertEquals(subs.map(_.topic.value).sorted, List("audit", "orders"))
