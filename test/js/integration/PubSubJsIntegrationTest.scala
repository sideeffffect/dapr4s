//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.OrderEvent
import munit.FunSuite
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[PublishCapability]] end to end: publish through the sidecar, the in-process [[JsItServerApp]] subscription (hosted
  * by [[ServerDaprJsItSuite]]) writes the payload to state, and the test polls state until the write is visible — the
  * Scala.js twin of [[PublishCapabilityServerTest]]'s delivery checks.
  *
  * The falsy-`0` test exercises the raw-fetch fallback in `PublishCapabilityImpl` (the JS SDK silently drops JS-falsy
  * request bodies — `if (params?.body)` in HTTPClient.js).
  */
@scala.caps.assumeSafe
class PubSubJsIntegrationTest extends FunSuite, ServerDaprJsItSuite:

  test("pubsub: publish is delivered to the server subscription and lands in state"):
    withDapr:
      val orderId = uniqueId()
      DaprCapability.publish(PubSub) {
        PublishCapability.publish(Topic("js-it-orders"), OrderEvent(orderId, "widget", 7))
      }
      DaprCapability.state(StateStore) {
        val qty = eventually(s"order $orderId visible in state") {
          StateCapability.get[Int](StateStoreKey(s"js-it-order-$orderId"))
        }
        assertEquals(qty, 7)
      }

  test("pubsub: falsy payload 0 goes through the raw-fetch fallback and is delivered intact"):
    withDapr:
      val marker = StateStoreKey("js-it-zero-marker")
      DaprCapability.state(StateStore) {
        StateCapability.delete(marker)
        DaprCapability.publish(PubSub) {
          PublishCapability.publish(Topic("js-it-zeros"), 0)
        }
        val received = eventually("zero marker visible in state") {
          StateCapability.get[Int](marker)
        }
        assertEquals(received, 0)
      }

  test("pubsub: bulkPublish delivers every entry"):
    withDapr:
      val ids = List(uniqueId(), uniqueId(), uniqueId())
      val entries = ids.zipWithIndex.map { case (id, i) =>
        BulkPublishEntry(BulkEntryId(s"entry-$i"), OrderEvent(id, "bulk", i + 1))
      }
      DaprCapability.publish(PubSub) {
        val result = PublishCapability.bulkPublish(Topic("js-it-orders"), entries)
        assertEquals(result.failedEntries, Nil)
      }
      DaprCapability.state(StateStore) {
        ids.zipWithIndex.foreach { case (id, i) =>
          val qty = eventually(s"bulk order $id visible in state") {
            StateCapability.get[Int](StateStoreKey(s"js-it-order-$id"))
          }
          assertEquals(qty, i + 1)
        }
      }
