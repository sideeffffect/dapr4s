package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.DaprAppValidationError.*
import munit.FunSuite

// --- Fixtures -------------------------------------------------------------

private class EchoActivity extends WorkflowActivity[Int, Int]:
  def execute(input: Int)(using DaprCapability): Int = input

private class DoubleActivity extends WorkflowActivity[Int, Int]:
  def execute(input: Int)(using DaprCapability): Int = input * 2

// Two workflow classes with the same *simple* name in different "packages" (objects) —
// the runtime registers by getSimpleName, so these collide silently.
private object pkgA:
  class OrderWorkflow extends Workflow:
    def run(using WorkflowContext): Unit = ()
private object pkgB:
  class OrderWorkflow extends Workflow:
    def run(using WorkflowContext): Unit = ()

private class ReportWorkflow extends Workflow:
  def run(using WorkflowContext): Unit = ()

@scala.caps.assumeSafe
class DaprAppValidationTest extends FunSuite:

  // Helpers that build single-kind handlers with trivial bodies.
  private def sub(topic: String): Subscription =
    Subscription[Int](PubSubName("pubsub"), Topic(topic))(_ => SubscriptionResult.Success)
  private def inv(method: String): InvocationRoute =
    InvocationRoute[Int, Int](InvocationMethodName(method))(identity)
  private def bind(name: String): BindingRoute =
    BindingRoute[Int](BindingName(name))(_ => ())
  private def job(name: String): JobRoute =
    JobRoute[Int](JobName(name))(_ => ())
  private def actor(tpe: String): ActorDefinition =
    ActorDefinition(ActorType(tpe))(_ => ActorRoutes())

  test("a clean app has no validation errors and validateOrThrow returns it unchanged"):
    val app = DaprApp(
      subscriptions = List(sub("orders"), sub("shipments")),
      invocations = List(inv("place-order"), inv("get-order")),
      bindings = List(bind("cron")),
      jobs = List(job("nightly")),
      workflows = List(new ReportWorkflow),
      activities = List(new EchoActivity, new DoubleActivity),
      actors = List(actor("Counter")),
    )
    assertEquals(app.validationErrors, Nil)
    assert(app.validateOrThrow() eq app)

  test("duplicate activity names are detected"):
    val app = DaprApp(activities = List(new EchoActivity, new EchoActivity))
    assertEquals(
      app.validationErrors,
      List(DuplicateActivityName(classOf[EchoActivity].getCanonicalName.nn, 2)),
    )

  test("duplicate workflow simple names (cross-package) are detected"):
    val app = DaprApp(workflows = List(new pkgA.OrderWorkflow, new pkgB.OrderWorkflow))
    assertEquals(app.validationErrors, List(DuplicateWorkflowName("OrderWorkflow", 2)))

  test("duplicate subscription routes are detected"):
    val app = DaprApp(subscriptions = List(sub("orders"), sub("orders")))
    assertEquals(app.validationErrors, List(DuplicateSubscriptionRoute("/orders", 2)))

  test("duplicate invocation methods are detected"):
    val app = DaprApp(invocations = List(inv("place-order"), inv("place-order")))
    assertEquals(app.validationErrors, List(DuplicateInvocationMethod("place-order", 2)))

  test("duplicate binding names are detected"):
    val app = DaprApp(bindings = List(bind("cron"), bind("cron")))
    assertEquals(app.validationErrors, List(DuplicateBindingName("cron", 2)))

  test("duplicate job names are detected"):
    val app = DaprApp(jobs = List(job("nightly"), job("nightly")))
    assertEquals(app.validationErrors, List(DuplicateJobName("nightly", 2)))

  test("duplicate actor types are detected"):
    val app = DaprApp(actors = List(actor("Counter"), actor("Counter")))
    assertEquals(app.validationErrors, List(DuplicateActorType("Counter", 2)))

  test("cross-type root-route collision (binding vs invocation on same path) is detected"):
    val app = DaprApp(bindings = List(bind("foo")), invocations = List(inv("foo")))
    assertEquals(app.validationErrors, List(RouteCollision("/foo", List("binding", "invocation"))))

  test("reserved-path collision is detected"):
    val app = DaprApp(invocations = List(inv("dapr/config")))
    assertEquals(
      app.validationErrors,
      List(ReservedPathCollision("/dapr/config", "invocation", "/dapr/config")),
    )

  test("a subscription route under the /actors prefix collides with the reserved prefix"):
    val app = DaprApp(subscriptions = List(sub("actors/whatever")))
    assertEquals(
      app.validationErrors,
      List(ReservedPathCollision("/actors/whatever", "pub/sub", "/actors")),
    )

  test("validateOrThrow aggregates every error into one exception"):
    val app = DaprApp(
      invocations = List(inv("dup"), inv("dup")),
      bindings = List(bind("dup")),
    )
    // Duplicate invocation + cross-type collision on /dup.
    val ex = intercept[DaprAppValidationException](app.validateOrThrow())
    assertEquals(ex.errors.size, 2)
    assert(ex.errors.contains(DuplicateInvocationMethod("dup", 2)))
    assert(ex.errors.contains(RouteCollision("/dup", List("binding", "invocation"))))
    assert(ex.getMessage.nn.contains("2 error(s)"))

  // --- Actor-internal checks (enforced at build time) ----------------------

  test("actor build with duplicate method names throws"):
    val defn = ActorDefinition(ActorType("Dup")) { _ =>
      ActorRoutes(methods =
        List(
          ActorMethodRoute[Int, Int](ActorMethodName("m"))(identity),
          ActorMethodRoute[Int, Int](ActorMethodName("m"))(identity),
        ),
      )
    }
    val ex = intercept[DaprAppValidationException](defn.build(ActorId("a"), FakeActorContext()))
    assertEquals(ex.errors, List(DuplicateActorMethod("Dup", "m", 2)))

  test("actor build with duplicate timer and reminder names throws listing both"):
    val defn = ActorDefinition(ActorType("Dup")) { _ =>
      ActorRoutes(
        reminders = List(
          ActorReminderRoute[Int](ReminderName("r"))(_ => ()),
          ActorReminderRoute[Int](ReminderName("r"))(_ => ()),
        ),
        timers = List(
          ActorTimerRoute[Int](TimerName("t"))(_ => ()),
          ActorTimerRoute[Int](TimerName("t"))(_ => ()),
        ),
      )
    }
    val ex = intercept[DaprAppValidationException](defn.build(ActorId("a"), FakeActorContext()))
    assertEquals(
      ex.errors,
      List(DuplicateActorReminder("Dup", "r", 2), DuplicateActorTimer("Dup", "t", 2)),
    )

  test("actor build with distinct route names succeeds"):
    val defn = ActorDefinition(ActorType("Ok")) { _ =>
      ActorRoutes(methods =
        List(
          ActorMethodRoute[Int, Int](ActorMethodName("a"))(identity),
          ActorMethodRoute[Int, Int](ActorMethodName("b"))(identity),
        ),
      )
    }
    val routes = defn.build(ActorId("a"), FakeActorContext())
    assertEquals(routes.methods.map(_.methodName.value), List("a", "b"))
