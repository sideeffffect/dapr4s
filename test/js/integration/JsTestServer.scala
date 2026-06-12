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
    DaprCapability.state(JsItEnv.StateStore) {
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
          Subscription[OrderEvent](JsItEnv.PubSub, Topic("js-it-orders")) { ev =>
            try
              StateCapability.save(StateStoreKey(s"js-it-order-${ev.data.orderId}"), ev.data.quantity)
              SubscriptionResult.Success
            catch case e: Exception => throw e
          },
          Subscription[Int](JsItEnv.PubSub, Topic("js-it-zeros")) { ev =>
            try
              StateCapability.save(StateStoreKey("js-it-zero-marker"), ev.data)
              SubscriptionResult.Success
            catch case e: Exception => throw e
          },
        ),
      )
    }

/** Entry point of the JS integration test server — the Scala.js twin of the JVM suites' in-test `DaprAppServer`
  * threads, but as a separate Node process because `serve` suspends forever (packaged and started by
  * `scripts/js-integration-env.sh up`, see the build incantation there).
  *
  * Hosts [[JsItServerApp]] plus the shared cross-platform fixtures: the `Counter` actor ([[CounterActorApp]]) and the
  * `AddingWorkflow` + derived `AddActivities` pair ([[WorkflowApp]]), plus [[GatedWorkflow]] for the raiseEvent path.
  * The single `js.async` at the program edge satisfies the Wasm/JSPI requirement documented on [[dapr4s.Dapr]].
  */
@main def jsTestServerMain(): Unit =
  js.async {
    println(s"[js-it-server] starting on port ${JsItEnv.AppPort}")
    Dapr(JsItEnv.serverConfig).serve {
      JsItServerApp() ++ CounterActorApp() ++ WorkflowApp() ++ DaprApp(workflows = List(new GatedWorkflow))
    }
  }: Unit
