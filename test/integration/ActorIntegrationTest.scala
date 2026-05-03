package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import dapr.safe.test.unit.MockActorContext
import munit.FunSuite

/** Unit-level tests for the Counter actor using [[TestDaprApp]] and [[MockActorContext]].
  *
  * These tests run fully in-process with no Dapr sidecar — they exercise the actor business logic, state management,
  * and reminder/timer registration via [[MockActorContext]].
  */
@scala.caps.assumeSafe
class ActorIntegrationTest extends FunSuite:

  // ---- helpers ---------------------------------------------------------------

  private def freshCtx(): MockActorContext = new MockActorContext

  private def callActor[Req: JsonCodec, Resp: JsonCodec](
      method: String,
      req: Req,
      ctx: MockActorContext = freshCtx(),
  ): (Resp, MockActorContext) =
    val resp = TestDaprApp.callActor[Req](
      CounterActorHandlers.daprApp,
      "Counter",
      "actor-1",
      method,
      req,
      ctx,
    )[Resp]
    (resp, ctx)

  // ---- increment -------------------------------------------------------------

  test("actor: increment from zero"):
    val (state, _) = callActor[IncrRequest, CounterState]("increment", IncrRequest(5))
    assertEquals(state.count, 5)

  test("actor: increment accumulates across calls with same context"):
    val ctx = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(3), ctx)
    val (state, _) = callActor[IncrRequest, CounterState]("increment", IncrRequest(7), ctx)
    assertEquals(state.count, 10)

  test("actor: increment by negative amount decrements"):
    val ctx = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(10), ctx)
    val (state, _) = callActor[IncrRequest, CounterState]("increment", IncrRequest(-3), ctx)
    assertEquals(state.count, 7)

  // ---- get ------------------------------------------------------------------

  test("actor: get returns 0 for fresh actor"):
    val (state, _) = callActor[Unit, CounterState]("get", (), freshCtx())
    assertEquals(state.count, 0)

  test("actor: get reflects incremented value"):
    val ctx = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(42), ctx)
    val (state, _) = callActor[Unit, CounterState]("get", (), ctx)
    assertEquals(state.count, 42)

  // ---- reset ----------------------------------------------------------------

  test("actor: reset brings count back to zero"):
    val ctx = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(100), ctx)
    val (state, _) = callActor[Unit, CounterState]("reset", (), ctx)
    assertEquals(state.count, 0)

  test("actor: get after reset returns 0"):
    val ctx = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(50), ctx)
    callActor[Unit, CounterState]("reset", (), ctx)
    val (state, _) = callActor[Unit, CounterState]("get", (), ctx)
    assertEquals(state.count, 0)

  // ---- reminder registration ------------------------------------------------

  test("actor: schedule-reset registers a reminder"):
    val (_, ctx) = callActor[Unit, Unit]("schedule-reset", (), freshCtx())
    val reminders = ctx.registeredReminders
    assert(reminders.contains("scheduled-reset"), s"Expected reminder not found: $reminders")
    val (_, dueTime, period) = reminders("scheduled-reset")
    assertEquals(dueTime, java.time.Duration.ofMinutes(1))
    assertEquals(period, None)

  test("actor: cancel-reset removes the reminder"):
    val ctx = freshCtx()
    callActor[Unit, Unit]("schedule-reset", (), ctx)
    callActor[Unit, Unit]("cancel-reset", (), ctx)
    assert(!ctx.registeredReminders.contains("scheduled-reset"))

  test("actor: cancel-reset on non-existent reminder is a no-op"):
    val (_, ctx) = callActor[Unit, Unit]("cancel-reset", (), freshCtx())
    assertEquals(ctx.registeredReminders.size, 0)

  // ---- timer registration ---------------------------------------------------

  test("actor: schedule-auto-increment registers a timer"):
    val (_, ctx) = callActor[Unit, Unit]("schedule-auto-increment", (), freshCtx())
    val timers = ctx.registeredTimers
    assert(timers.contains("auto-increment"), s"Expected timer not found: $timers")
    val (_, dueTime, _) = timers("auto-increment")
    assertEquals(dueTime, java.time.Duration.ofMillis(500))

  // ---- reminder callback dispatch -------------------------------------------

  test("actor: reminder callback resets counter"):
    val ctx = freshCtx()
    ctx.seedState[Int](StateKey("count"), 77)
    TestDaprApp.deliverReminder(
      CounterActorHandlers.daprApp,
      "Counter",
      "actor-1",
      "scheduled-reset",
      "reset",
      ctx,
    )
    val (state, _) = callActor[Unit, CounterState]("get", (), ctx)
    assertEquals(state.count, 0)

  // ---- timer callback dispatch ----------------------------------------------

  test("actor: timer callback increments counter"):
    val ctx = freshCtx()
    ctx.seedState[Int](StateKey("count"), 10)
    TestDaprApp.deliverTimer(
      CounterActorHandlers.daprApp,
      "Counter",
      "actor-1",
      "auto-increment",
      IncrRequest(1),
      ctx,
    )
    assertEquals(ctx.get[Int](StateKey("count")), Some(11))

  // ---- unknown actor / method errors ----------------------------------------

  test("actor: unknown actor type throws NoSuchElementException"):
    intercept[java.util.NoSuchElementException]:
      TestDaprApp.callActor[Unit](
        CounterActorHandlers.daprApp,
        "NonExistentActor",
        "x",
        "get",
        (),
        freshCtx(),
      )[CounterState]

  test("actor: unknown method throws NoSuchElementException"):
    intercept[java.util.NoSuchElementException]:
      TestDaprApp.callActor[Unit](
        CounterActorHandlers.daprApp,
        "Counter",
        "1",
        "no-such-method",
        (),
        freshCtx(),
      )[CounterState]

  test("actor: unknown reminder name throws NoSuchElementException"):
    intercept[java.util.NoSuchElementException]:
      TestDaprApp.deliverReminder(
        CounterActorHandlers.daprApp,
        "Counter",
        "1",
        "nonexistent-reminder",
        "data",
        freshCtx(),
      )

  test("actor: unknown timer name throws NoSuchElementException"):
    intercept[java.util.NoSuchElementException]:
      TestDaprApp.deliverTimer(
        CounterActorHandlers.daprApp,
        "Counter",
        "1",
        "nonexistent-timer",
        IncrRequest(1),
        freshCtx(),
      )

  // ---- DaprApp composition -------------------------------------------------

  test("actor: DaprApp ++ merges actor definitions"):
    val app1 = CounterActorHandlers.daprApp
    val app2 = DaprApp(actors =
      List(
        ActorDefinition(ActorType("Other")) { (_, _) => ActorRoutes() },
      ),
    )
    val combined = app1 ++ app2
    assertEquals(combined.actors.size, 2)
    assert(combined.actors.exists(_.actorType.value == "Counter"))
    assert(combined.actors.exists(_.actorType.value == "Other"))

  // ---- state isolation by actor ID -----------------------------------------

  test("actor: different actor IDs have independent state"):
    val ctx1 = freshCtx()
    val ctx2 = freshCtx()
    callActor[IncrRequest, CounterState]("increment", IncrRequest(10), ctx1)
    callActor[IncrRequest, CounterState]("increment", IncrRequest(20), ctx2)
    val (state1, _) = callActor[Unit, CounterState]("get", (), ctx1)
    val (state2, _) = callActor[Unit, CounterState]("get", (), ctx2)
    assertEquals(state1.count, 10)
    assertEquals(state2.count, 20)
