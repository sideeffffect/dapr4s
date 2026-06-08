package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite

/** A reifiable actor: a class with an `ActorId` constructor and `(using ActorContext)` methods. */
class Counter(actorId: ActorId):
  def increment(input: Req)(using ActorContext): Resp =
    ActorContext.set(ActorStateKey("n"), input.n)
    Resp(s"inc-${actorId.value}")
  def get()(using ActorContext): Resp =
    Resp("got")
  @reminder def onReset(msg: String)(using ActorContext): Unit = ()
  @timer def tick(req: Req)(using ActorContext): Unit = ()

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

  test("ActorDefinitions.derive(actorType) overrides the class-name-derived ActorType"):
    val defn = ActorDefinitions.derive[Counter](ActorType("counter-actor"))
    assertEquals(defn.actorType, ActorType("counter-actor"))
    assertEquals(
      defn.build(ActorId("a1"), FakeActorContext()).methods.map(_.methodName.value).sorted,
      List("get", "increment"),
    )
