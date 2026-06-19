package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.OrderEvent
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import unsafeExceptions.canThrowAny

/** [[PublishCapability]] integration suite — a SINGLE cross-platform file. End to end: publish through the sidecar, the
  * union server's [[dapr4s.test.integration.apps.ItServerApp]] subscription writes the payload to state, and the test
  * polls state until the write is visible. Bring-up comes from `ServerDaprItSuite` (one implementation per platform).
  *
  * The falsy-`0` test guards the raw-fetch fallback in the JS `PublishCapabilityImpl` (the JS SDK silently drops
  * JS-falsy request bodies); it is a harmless regression guard on the JVM, where it simply round-trips.
  */
@scala.caps.assumeSafe
class PubSubItTest extends FunSuite, ServerDaprItSuite:

  test("pubsub: publish is delivered to the server subscription and lands in state"):
    withDapr:
      val orderId = ItNames.fresh("o")
      DaprCapability.publish(ItNames.PubSub) {
        PublishCapability.publish(Topic("it-orders"), OrderEvent(orderId, "widget", 7))
      }
      DaprCapability.state(ItNames.StateStore) {
        val qty = eventually(s"order $orderId visible in state") {
          StateCapability.get[Int](StateStoreKey(s"it-order-$orderId"))
        }
        assertEquals(qty, 7)
      }

  test("pubsub: falsy payload 0 goes through and is delivered intact"):
    withDapr:
      val marker = StateStoreKey("it-zero-marker")
      DaprCapability.state(ItNames.StateStore) {
        StateCapability.delete(marker)
        DaprCapability.publish(ItNames.PubSub) {
          PublishCapability.publish(Topic("it-zeros"), 0)
        }
        val received = eventually("zero marker visible in state") {
          StateCapability.get[Int](marker)
        }
        assertEquals(received, 0)
      }

  test("pubsub: bulkPublish delivers every entry"):
    withDapr:
      val ids = List(ItNames.fresh("o"), ItNames.fresh("o"), ItNames.fresh("o"))
      val entries = ids.zipWithIndex.map { case (id, i) =>
        BulkPublishEntry(BulkEntryId(s"entry-$i"), OrderEvent(id, "bulk", i + 1))
      }
      DaprCapability.publish(ItNames.PubSub) {
        assertEquals(PublishCapability.bulkPublish(Topic("it-orders"), entries).failedEntries, Nil)
      }
      DaprCapability.state(ItNames.StateStore) {
        ids.zipWithIndex.foreach { case (id, i) =>
          val qty = eventually(s"bulk order $id visible in state") {
            StateCapability.get[Int](StateStoreKey(s"it-order-$id"))
          }
          assertEquals(qty, i + 1)
        }
      }

  // ---- publish smoke (no subscriber on `it-smoke`) ----------------------------

  test("pubsub: publish string/int payloads and publishWithMetadata do not throw"):
    withDapr:
      DaprCapability.publish(ItNames.PubSub) {
        PublishCapability.publish(Topic("it-smoke"), "hello-pubsub")
        PublishCapability.publish(Topic("it-smoke"), 42)
        PublishCapability.publishWithMetadata(
          Topic("it-smoke"),
          "with-metadata",
          Map(MetadataKey("traceparent") -> MetadataValue("00-abc-def-01")),
        )
      }
