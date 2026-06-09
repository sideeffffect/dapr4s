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
  * ambient `using` dependency (`store`). Bound to the contract via
  * `InvokeRoutes.deriveChecked[Greeter, GreeterImpl.type]`.
  */
object GreeterImpl:
  def double(req: Req): Resp = Resp(s"double${req.n}")
  def plain(req: Req)(using r: Recorder): Resp =
    r.log += s"plain${req.n}"
    Resp("plain")
  def stats(): Resp = Resp("stats") // contract's @name("get-stats") governs the wire name
  def echo(req: Resp): Resp = req

/** Input-binding handlers: payload in, Unit out, keyed by binding name. */
object IngestHandlers:
  def orders(payload: Req)(using r: Recorder): Unit = r.log += s"ingest${payload.n}"
  @name("audit-log") def audit(payload: Req): Unit = ()

/** Job-trigger handlers keyed by job name, plus the scheduling contract they answer. */
trait ReportJobs:
  def nightly(spec: Req, schedule: JobSchedule)(using JobsCapability, JsonCodec[Req]): Unit
  @name("nightly") def status()(using JobsCapability): Option[JobDetails] // getter: not a trigger

object ReportJobHandlers:
  def nightly(spec: Req)(using r: Recorder): Unit = r.log += s"job${spec.n}"

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

  test("InvokeRoutes.deriveChecked: wire names from contract, dispatch to plain impl"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val routes = InvokeRoutes.deriveChecked[Greeter, GreeterImpl.type]
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

  test("Subscriptions.deriveChecked: checks publisher contract by topic, dispatches"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    // Publisher (orders/audit topics, Req payload) checked against SubHandlers (same topics, CloudEvent[Req]).
    val subs = Subscriptions.deriveChecked[Publisher, SubHandlers.type](PubSubName("pubsub"))
    assertEquals(subs.map(_.topic.value).sorted, List("audit", "orders"))
    val orders = subs.find(_.topic.value == "orders").get
    orders.rawHandler.asInstanceOf[Any => Any](ce(Req(11)))
    assertEquals(rec.log.toList, List("order11"))

  test("BindingRoutes.derive: binding names, @name, dispatch to plain handler"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val routes = BindingRoutes.derive[IngestHandlers.type]
    assertEquals(routes.map(_.bindingName.value).sorted, List("audit-log", "orders"))
    val orders = routes.find(_.bindingName.value == "orders").get
    orders.rawHandler.asInstanceOf[Any => Any](Req(8))
    assertEquals(rec.log.toList, List("ingest8"))

  test("JobRoutes.derive: job names, @name, dispatch to plain handler"):
    given Recorder = new Recorder { val log = mutable.ListBuffer.empty[String] }
    val routes = JobRoutes.derive[ReportJobHandlers.type]
    assertEquals(routes.map(_.name.value), List("nightly"))

  test("JobRoutes.deriveChecked: checks scheduling contract by job name, skips getters"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    // ReportJobs schedules "nightly" (payload Req) and has a "nightly" getter (skipped).
    val routes = JobRoutes.deriveChecked[ReportJobs, ReportJobHandlers.type]
    assertEquals(routes.map(_.name.value), List("nightly"))
    routes.head.rawHandler.asInstanceOf[Any => Any](Req(2))
    assertEquals(rec.log.toList, List("job2"))

  test("Subscriptions.derive without pubsubName uses the handler type's simple name"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    val subs = Subscriptions.derive[SubHandlers.type]
    assertEquals(subs.map(_.pubsubName.value).distinct, List("SubHandlers"))
    assertEquals(subs.map(_.topic.value).sorted, List("audit", "orders"))
