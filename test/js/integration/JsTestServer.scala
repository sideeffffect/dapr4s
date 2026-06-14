//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import scala.scalajs.js
import unsafeExceptions.canThrowAny

/** Workflow that parks on an external event and completes with a value derived from its payload — the raiseEvent
  * counterpart of [[dapr4s.test.integration.apps.AddingWorkflow]] (which exercises the activity path).
  * `WorkflowJsIntegrationTest` starts it, raises `go`, and asserts on the tripled output (x3 to be distinguishable from
  * AddActivities' doubling).
  */
class GatedWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val gate = WorkflowContext.waitForExternalEvent[IncrRequest](EventName("go")).await()
    WorkflowContext.complete(CounterState(gate.amount * 3))

/** The inbound handler set the JS integration suites exercise through a real sidecar:
  *
  *   - `echo` / `double` invoke routes — the same method names the derived [[EchoService]] caller facade expects, so
  *     `InvokeJsIntegrationTest` covers both the plain and the derived invoke path;
  *   - `echo-int` — Int→Int identity, so a falsy `0` request body (the raw-fetch fallback in the JS client) round-trips
  *     end to end;
  *   - `js-it-orders` subscription — writes each event's quantity to state under `js-it-order-{orderId}`, letting
  *     `PubSubJsIntegrationTest` poll state for delivery;
  *   - `js-it-zeros` subscription — writes the (falsy) Int payload to the fixed `js-it-zero-marker` key.
  */
object JsItServerApp:
  def apply()(using DaprCapability): DaprApp =
    DaprCapability.state(ItNames.StateStore) {
      DaprApp(
        invokeRoutes = List(
          InvokeRoute[String, String](InvokeMethodName("echo")) { s =>
            try s
            catch case e: Exception => throw e
          },
          InvokeRoute[IncrRequest, CounterState](InvokeMethodName("double")) { req =>
            try CounterState(req.amount * 2)
            catch case e: Exception => throw e
          },
          InvokeRoute[Int, Int](InvokeMethodName("echo-int")) { i =>
            try i
            catch case e: Exception => throw e
          },
        ),
        subscriptions = List(
          Subscription[OrderEvent](ItNames.PubSub, Topic("js-it-orders")) { ev =>
            try
              StateCapability.save(StateStoreKey(s"js-it-order-${ev.data.orderId}"), ev.data.quantity)
              SubscriptionResult.Success
            catch case e: Exception => throw e
          },
          Subscription[Int](ItNames.PubSub, Topic("js-it-zeros")) { ev =>
            try
              StateCapability.save(StateStoreKey("js-it-zero-marker"), ev.data)
              SubscriptionResult.Success
            catch case e: Exception => throw e
          },
        ),
      )
    }

/** The union of every inbound handler set the server-delivery suites need — invoke/pub-sub ([[JsItServerApp]]), the
  * `Counter` actor ([[CounterActorApp]]), and the workflows ([[WorkflowApp]] + [[GatedWorkflow]]).
  *
  * This is hosted ONCE in-process by [[DaprJsIt.sharedServerConfig]] (via `Dapr(serverCfg).serveAsync`, reachable from
  * the daprd container through `host.testcontainers.internal`) — the Scala.js twin of the JVM suites' in-test
  * `DaprAppServer`. Unlike the JVM, where each server suite starts and stops its own app-server thread in `afterAll`,
  * on JS `serve` suspends forever with no clean stop, so the four server-delivery suites (Actor/PubSub/Invoke/Workflow)
  * share ONE sidecar + ONE union server for the whole run (the topology of the retired `scripts/js-integration-env.sh`,
  * now driven by testcontainers). There is no standalone server `@main` any more: the fixture fire-and-forgets
  * `serveAsync` and lets the JSPI event loop multiplex it with the test fibers.
  */
def jsItUnionApp(using DaprCapability): DaprApp =
  JsItServerApp() ++ CounterActorApp() ++ WorkflowApp() ++ DaprApp(workflows = List(new GatedWorkflow))
