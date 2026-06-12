//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[StateCapability]] against a real `state.redis` component, on the Wasm+JSPI backend — the Scala.js twin of
  * [[StateCapabilityServerTest]]'s coverage (minus the HTTP-dispatch wrapping: here the capability itself IS the thing
  * under test, called directly inside `Dapr.run`).
  *
  * Every munit body is `js.async { ... }.toFuture` — never a raw `js.Promise`, which munit would NOT await (verified
  * footgun: a vacuous pass). Requires the environment from `scripts/js-integration-env.sh up`.
  */
@scala.caps.assumeSafe
class StateJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  private def uniqueKey() = StateStoreKey(s"js-it-k-${uniqueId()}")

  test("state: save then get returns the saved value"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val k = uniqueKey()
          StateCapability.save(k, "hello-js")
          assertEquals(StateCapability.get[String](k), Some("hello-js"))
        }
    }.toFuture

  test("state: get for a missing key returns None"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          assertEquals(StateCapability.get[String](uniqueKey()), None)
        }
    }.toFuture

  test("state: getWithETag returns value and etag after save"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val k = uniqueKey()
          StateCapability.save(k, "etagged")
          val entry = StateCapability.getWithETag[String](k)
          assertEquals(entry.value, Some("etagged"))
          assert(entry.etag.isDefined, "ETag should be present after save")
        }
    }.toFuture

  test("state: saveWithETag succeeds with the current etag and conflicts with a wrong one"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val k = uniqueKey()
          StateCapability.save(k, "v1")
          val etag = StateCapability.getWithETag[String](k).etag.getOrElse(fail("expected an etag"))
          assertEquals(StateCapability.saveWithETag(k, "v2", etag), None)
          // The successful save bumped the server-side etag, so the one captured above is now
          // STALE — a genuine optimistic-concurrency conflict. A fabricated string would not do
          // here: Redis etags are integers, and daprd rejects a non-numeric etag with
          // 400 ERR_STATE_SAVE (invalid etag value) instead of reporting a conflict.
          assert(
            StateCapability.saveWithETag(k, "v3", etag).isDefined,
            "stale etag should yield a conflict",
          )
          assertEquals(StateCapability.get[String](k), Some("v2"))
        }
    }.toFuture

  test("state: delete removes a key"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val k = uniqueKey()
          StateCapability.save(k, "bye")
          StateCapability.delete(k)
          assertEquals(StateCapability.get[String](k), None)
        }
    }.toFuture

  test("state: saveBulk persists all entries and getBulk reads them (None for absent)"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val k1 = uniqueKey()
          val k2 = uniqueKey()
          val absent = uniqueKey()
          StateCapability.saveBulk[String](List(k1 -> "alpha", k2 -> "beta"))
          val results = StateCapability.getBulk[String](List(k1, k2, absent))
          assertEquals(results.get(k1).flatMap(_.value), Some("alpha"))
          assertEquals(results.get(k2).flatMap(_.value), Some("beta"))
          assertEquals(results.get(absent).flatMap(_.value), None)
        }
    }.toFuture

  test("state: transaction upserts and deletes atomically"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.state(StateStore) {
          val kAdd = uniqueKey()
          val kDel = uniqueKey()
          StateCapability.save(kDel, "gone")
          StateCapability.transaction(
            Seq(
              StateOp.UpsertOp[String](kAdd, "new"),
              StateOp.DeleteOp(kDel),
            ),
          )
          assertEquals(StateCapability.get[String](kAdd), Some("new"))
          assertEquals(StateCapability.get[String](kDel), None)
        }
    }.toFuture
