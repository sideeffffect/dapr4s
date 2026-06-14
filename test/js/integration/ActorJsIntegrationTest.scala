//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterActorApp, CounterState, IncrRequest}
import munit.FunSuite
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[ActorCapability]] against the `Counter` actor hosted in-process by [[ServerDaprJsItSuite]] ([[CounterActorApp]] —
  * the same cross-platform fixture the JVM [[ActorCapabilityServerTest]] hosts), with actor state in the real
  * `state.redis` actor state store and the placement service the testcontainer manages.
  *
  * The first call retries: the sidecar answers 500 for actor invocations until the placement service has disseminated
  * the actor type table — the same startup race the JVM twin polls through.
  */
@scala.caps.assumeSafe
class ActorJsIntegrationTest extends FunSuite, ServerDaprJsItSuite:

  private def uniqueActorId() = ActorId(fresh("js-it-actor"))

  test("actor: increments accumulate and get reads the final count"):
    withDapr:
      DaprCapability.actor(CounterActorApp.ActorTypeName, uniqueActorId()) {
        val first = retryUntilSuccess("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(2))[CounterState]
        }
        assertEquals(first, CounterState(2))
        val second = ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(3))[CounterState]
        assertEquals(second, CounterState(5))
        val read = ActorCapability.invoke(ActorMethodName("get"), ())[CounterState]
        assertEquals(read, CounterState(5))
      }

  test("actor: state is isolated per actor id"):
    withDapr:
      val cap = summon[DaprCapability]
      val idA = uniqueActorId()
      val idB = uniqueActorId()
      DaprCapability.actor(CounterActorApp.ActorTypeName, idA) {
        val a = retryUntilSuccess("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(11))[CounterState]
        }
        assertEquals(a, CounterState(11))
      }(using cap)
      DaprCapability.actor(CounterActorApp.ActorTypeName, idB) {
        val b = ActorCapability.invoke(ActorMethodName("get"), ())[CounterState]
        assertEquals(b, CounterState(0))
      }(using cap)

  test("actor: reset brings the count back to zero"):
    withDapr:
      DaprCapability.actor(CounterActorApp.ActorTypeName, uniqueActorId()) {
        val incremented = retryUntilSuccess("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(100))[CounterState]
        }
        assertEquals(incremented, CounterState(100))
        assertEquals(ActorCapability.invoke(ActorMethodName("reset"), ())[CounterState], CounterState(0))
        assertEquals(ActorCapability.invoke(ActorMethodName("get"), ())[CounterState], CounterState(0))
      }
