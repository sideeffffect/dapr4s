package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite

@scala.caps.assumeSafe
class WorkflowActivityDerivationTest extends FunSuite:

  // A DaprCapability is supplied to execute but never used by these activity bodies.
  private def noDapr: DaprCapability = null.asInstanceOf[DaprCapability]

  test("WorkflowActivities.derive reifies one named activity per method and dispatches to it"):
    val activities = WorkflowActivities.derive[CounterActivities]

    assertEquals(
      activities.map(_.activityName).sorted,
      List(
        "dapr4s.test.unit.CounterActivities#add",
        "dapr4s.test.unit.CounterActivities#clear",
        "dapr4s.test.unit.CounterActivities#echo",
      ),
    )

    val add = activities.find(_.activityName.endsWith("#add")).get.asInstanceOf[WorkflowActivity[Req, Resp]]
    assertEquals(add.execute(Req(5))(using noDapr), Resp("added-5"))

    val clear = activities.find(_.activityName.endsWith("#clear")).get.asInstanceOf[WorkflowActivity[Unit, Resp]]
    assertEquals(clear.execute(())(using noDapr), Resp("reset"))

    // `echo` declares an extra `using JsonCodec[Req]`; the engine summons it at the derive site.
    val echo = activities.find(_.activityName.endsWith("#echo")).get.asInstanceOf[WorkflowActivity[Req, Resp]]
    assertEquals(echo.execute(Req(9))(using noDapr), Resp("9"))

  test("WorkflowActivityCalls.derive forwards to callActivity under the same name as the reified activity"):
    val fake = FakeActivityContext("scheduled")
    given WorkflowContext = fake
    val calls = CounterCalls // CounterCalls = WorkflowActivityCalls.derive[…] (unchecked)

    assertEquals(calls.add(Req(7)).await(), Resp("scheduled"))
    assertEquals(calls.reset().await(), Resp("scheduled"))

    assertEquals(
      fake.log.toList,
      List(
        "call|dapr4s.test.unit.CounterActivities#add|7",
        "call|dapr4s.test.unit.CounterActivities#clear",
      ),
    )

  test("WorkflowActivityCalls.deriveChecked verifies against the impl and forwards identically"):
    val fake = FakeActivityContext("scheduled")
    given WorkflowContext = fake
    val calls = WorkflowActivityCalls.deriveChecked[CounterCalls, CounterActivities]
    assertEquals(calls.add(Req(7)).await(), Resp("scheduled"))
    assertEquals(fake.log.toList, List("call|dapr4s.test.unit.CounterActivities#add|7"))

  test("WorkflowActivities.deriveChecked verifies the impl against the caller trait and reifies all activities"):
    val activities = WorkflowActivities.deriveChecked[CounterCalls, CounterActivities]
    // CounterCalls covers add/reset; the extra `echo` activity is still reified.
    assertEquals(
      activities.map(_.activityName).sorted,
      List(
        "dapr4s.test.unit.CounterActivities#add",
        "dapr4s.test.unit.CounterActivities#clear",
        "dapr4s.test.unit.CounterActivities#echo",
      ),
    )
    val add = activities.find(_.activityName.endsWith("#add")).get.asInstanceOf[WorkflowActivity[Req, Resp]]
    assertEquals(add.execute(Req(5))(using noDapr), Resp("added-5"))
