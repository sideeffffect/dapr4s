package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite
import scala.concurrent.duration.*

/** A reifiable actor: a class with an `ActorId` constructor and `(using ActorContext)` methods. */
class Counter(actorId: ActorId):
  def increment(input: Req)(using ActorContext): Resp =
    ActorContext.set(ActorStateKey("n"), input.n)
    Resp(s"inc-${actorId.value}")
  def get()(using ActorContext): Resp =
    Resp("got")
  @reminder def onReset(msg: String)(using ActorContext): Unit = ()
  @timer def tick(req: Req)(using ActorContext): Unit = ()

/** Caller contract for [[Counter]]: the actor methods a client may invoke, plus the reminders/timers it may schedule
  * (`@reminder`/`@timer` methods forward to `ActorContext.registerReminder`/`registerTimer`). Binds both sides through
  * `Actor.derive[CounterActorClient]` ↔ `ActorDefinitions.deriveChecked[CounterActorClient, Counter]`.
  */
trait CounterActorClient:
  def increment(req: Req)(using ActorCapability, JsonCodec[Req], JsonCodec[Resp]): Resp
  def get()(using ActorCapability, JsonCodec[Resp]): Resp
  @reminder def onReset(msg: String, dueTime: FiniteDuration, period: Option[FiniteDuration] = None)(using
      ActorContext,
      JsonCodec[String],
  ): Unit
  @timer def tick(req: Req, dueTime: FiniteDuration, period: Option[FiniteDuration] = None)(using
      ActorContext,
      JsonCodec[Req],
  ): Unit

@scala.caps.assumeSafe
class ActorDefinitionsTest extends FunSuite:

  test("ActorDefinitions.derive reifies routes and dispatches to the actor instance"):
    val defn = ActorDefinitions.derive[Counter]
    assertEquals(defn.actorType, ActorType("Counter"))

    val ctx = FakeActorContext()
    val routes = defn.build(ActorId("a1"), ctx)

    assertEquals(routes.methods.map(_.methodName.value).sorted, List("get", "increment"))
    assertEquals(routes.reminders.map(_.reminderName.value), List("onReset"))
    assertEquals(routes.timers.map(_.timerName.value), List("tick"))

    val inc = routes.methods.find(_.methodName.value == "increment").get
    val out = inc.rawHandler.asInstanceOf[Any => Any](Req(5))
    assertEquals(out, Resp("inc-a1"))
    assertEquals(ctx.log.toList, List("set|n|5"))

  test("Actor.derive schedules reminders/timers via ActorContext (the dual of ActorDefinitions' routes)"):
    val ctx = FakeActorContext()
    given ActorContext = ctx
    val client = Actor.derive[CounterActorClient]
    client.onReset("hi", 1.hour)
    client.tick(Req(3), 30.seconds, Some(5.seconds))
    assertEquals(
      ctx.log.toList,
      List(
        s"reminder|onReset|${summon[JsonCodec[String]].encode("hi")}|1 hour|None",
        s"timer|tick|${summon[JsonCodec[Req]].encode(Req(3))}|30 seconds|Some(5 seconds)",
      ),
    )

  test("ActorDefinitions.deriveChecked checks the caller contract and reifies all routes"):
    val defn = ActorDefinitions.deriveChecked[CounterActorClient, Counter]
    assertEquals(defn.actorType, ActorType("Counter"))
    val routes = defn.build(ActorId("a1"), FakeActorContext())
    // the contract covers increment/get; reminders & timers are still reified
    assertEquals(routes.methods.map(_.methodName.value).sorted, List("get", "increment"))
    assertEquals(routes.reminders.map(_.reminderName.value), List("onReset"))
    assertEquals(routes.timers.map(_.timerName.value), List("tick"))

  test("ActorDefinitions.derive(actorType) overrides the class-name-derived ActorType"):
    val defn = ActorDefinitions.derive[Counter](ActorType("counter-actor"))
    assertEquals(defn.actorType, ActorType("counter-actor"))
    assertEquals(
      defn.build(ActorId("a1"), FakeActorContext()).methods.map(_.methodName.value).sorted,
      List("get", "increment"),
    )
