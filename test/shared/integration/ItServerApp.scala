package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterActorApp, CounterState, IncrRequest, OrderEvent, WorkflowApp}
import unsafeExceptions.canThrowAny

/** Workflow that parks on an external event and completes with a value derived from its payload — the raiseEvent
  * counterpart of [[dapr4s.test.integration.apps.AddingWorkflow]] (which exercises the activity path). `WorkflowItTest`
  * starts it, raises `go`, and asserts on the tripled output (x3 to be distinguishable from AddActivities' doubling).
  *
  * Cross-platform: `Task.await()` (here on the `waitForExternalEvent` task) is the same suspension primitive
  * `AddingWorkflow` uses on the activity task, so this workflow definition links on both the JVM and Scala.js.
  *
  * Lives here (next to the union app) rather than in `test/shared/apps` because the union app references [[ItNames]],
  * which is integration-only — both are excluded from the plain-JS unit-test leg.
  */
class GatedWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val gate = WorkflowContext.waitForExternalEvent[IncrRequest](EventName("go")).await()
    WorkflowContext.complete(CounterState(gate.amount * 3))

/** The inbound invoke/pub-sub handler set the server-delivery integration suites exercise through a real sidecar:
  *
  *   - `echo` / `double` invoke routes — the method names the derived [[dapr4s.test.integration.apps.EchoService]]
  *     caller facade expects, so `InvokeItTest` covers both the plain and the derived invoke path;
  *   - `echo-int` — Int→Int identity, so a falsy `0` request body round-trips end to end;
  *   - `it-orders` subscription — writes each event's quantity to state under `it-order-{orderId}`, letting
  *     `PubSubItTest` poll state for delivery;
  *   - `it-zeros` subscription — writes the (falsy) Int payload to the fixed `it-zero-marker` key.
  */
object ItServerApp:
  def apply()(using DaprCapability): DaprApp =
    DaprCapability.state(ItNames.StateStore) {
      DaprApp(
        invokeRoutes = List(
          InvokeRoute[String, String](InvokeMethodName("echo"))(s => s),
          InvokeRoute[IncrRequest, CounterState](InvokeMethodName("double"))(req => CounterState(req.amount * 2)),
          InvokeRoute[Int, Int](InvokeMethodName("echo-int"))(i => i),
        ),
        subscriptions = List(
          Subscription[OrderEvent](ItNames.PubSub, Topic("it-orders")) { ev =>
            StateCapability.save(StateStoreKey(s"it-order-${ev.data.orderId}"), ev.data.quantity)
            SubscriptionResult.Success
          },
          Subscription[Int](ItNames.PubSub, Topic("it-zeros")) { ev =>
            StateCapability.save(StateStoreKey("it-zero-marker"), ev.data)
            SubscriptionResult.Success
          },
        ),
      )
    }

/** The union of every inbound handler set the server-delivery suites need — invoke/pub-sub ([[ItServerApp]]), the
  * `Counter` actor ([[dapr4s.test.integration.apps.CounterActorApp]]), and the workflows
  * ([[dapr4s.test.integration.apps.WorkflowApp]] + [[GatedWorkflow]]).
  *
  * Hosted once per platform by the `ServerDaprItSuite` fixture (`Dapr(config).serve` on a virtual thread on the JVM;
  * `Dapr(cfg).serveAsync` in-process on Scala.js), reachable from the daprd container via
  * `host.testcontainers.internal`.
  */
def itUnionApp(using DaprCapability): DaprApp =
  ItServerApp() ++ CounterActorApp() ++ WorkflowApp() ++ DaprApp(workflows = List(new GatedWorkflow))
