package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterActorApp, CounterState, IncrRequest}
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[ActorCapability]] integration suite — a SINGLE cross-platform file: the calls + assertions against the `Counter`
  * actor ([[CounterActorApp]]) hosted by the union server, with actor state in the real `state.redis` actor state store
  * and the placement service the sidecar manages. Bring-up comes from `ServerDaprItSuite` (one implementation per
  * platform under the same name).
  *
  * The first call to a freshly-placed actor retries: the sidecar answers 500 for actor invocations until the placement
  * service has disseminated the actor-type table.
  *
  * State persistence, isolation, reset, and the reminder/timer callback loops are all exercised through
  * [[ActorCapability.invoke]] (the production client path); the JVM-only HTTP routing/structural checks live in the
  * unit `ActorServerRoutingTest`.
  */
@scala.caps.assumeSafe
class ActorItTest extends FunSuite, ServerDaprItSuite:

  private def uniqueActorId() = ActorId(ItNames.fresh("it-actor"))
  private val Counter = CounterActorApp.ActorTypeName

  test("actor: increments accumulate and get reads the final count"):
    withDapr:
      DaprCapability.actor(Counter, uniqueActorId()) {
        val first = retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(2))[CounterState]
        }
        assertEquals(first, CounterState(2))
        assertEquals(
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(3))[CounterState],
          CounterState(5),
        )
        assertEquals(ActorCapability.invoke(ActorMethodName("get"), ())[CounterState], CounterState(5))
      }

  test("actor: state is isolated per actor id"):
    withDapr:
      val cap = summon[DaprCapability]
      val idA = uniqueActorId()
      val idB = uniqueActorId()
      DaprCapability.actor(Counter, idA) {
        val a = retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(11))[CounterState]
        }
        assertEquals(a, CounterState(11))
      }(using cap)
      DaprCapability.actor(Counter, idB) {
        assertEquals(ActorCapability.invoke(ActorMethodName("get"), ())[CounterState], CounterState(0))
      }(using cap)

  test("actor: reset brings the count back to zero"):
    withDapr:
      DaprCapability.actor(Counter, uniqueActorId()) {
        val incremented = retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(100))[CounterState]
        }
        assertEquals(incremented, CounterState(100))
        assertEquals(ActorCapability.invoke(ActorMethodName("reset"), ())[CounterState], CounterState(0))
        assertEquals(ActorCapability.invoke(ActorMethodName("get"), ())[CounterState], CounterState(0))
      }

  test("actor: a real sidecar-fired reminder resets the counter"):
    withDapr:
      DaprCapability.actor(Counter, uniqueActorId()) {
        retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(77))[CounterState]
        }
        // 1-second reminder registered through the sidecar (so the write is authorised); poll until it fires.
        ActorCapability.invoke(ActorMethodName("schedule-quick-reset"), ())[Unit]
        val reset = eventually("counter reset by reminder", timeoutMs = 10000, intervalMs = 200) {
          val c = ActorCapability.invoke(ActorMethodName("get"), ())[CounterState]
          Option.when(c.count == 0)(c)
        }
        assertEquals(reset, CounterState(0))
      }

  test("actor: cancelling the reminder stops it from firing"):
    withDapr:
      DaprCapability.actor(Counter, uniqueActorId()) {
        retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(42))[CounterState]
        }
        ActorCapability.invoke(ActorMethodName("schedule-quick-reset"), ())[Unit]
        ActorCapability.invoke(ActorMethodName("cancel-reset"), ())[Unit]
        // Wait past the reminder dueTime to confirm it never fired.
        sleep(3000)
        assertEquals(ActorCapability.invoke(ActorMethodName("get"), ())[CounterState], CounterState(42))
      }

  test("actor: a real sidecar-fired timer increments the counter"):
    withDapr:
      DaprCapability.actor(Counter, uniqueActorId()) {
        retrying("actor placement ready") {
          ActorCapability.invoke(ActorMethodName("increment"), IncrRequest(10))[CounterState]
        }
        // 1-second one-shot timer registered through the sidecar; poll until it increments the counter.
        ActorCapability.invoke(ActorMethodName("schedule-auto-increment"), ())[Unit]
        val incremented = eventually("counter incremented by timer", timeoutMs = 10000, intervalMs = 200) {
          val c = ActorCapability.invoke(ActorMethodName("get"), ())[CounterState]
          Option.when(c.count == 11)(c)
        }
        assertEquals(incremented, CounterState(11))
      }
