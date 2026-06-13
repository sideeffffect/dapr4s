//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[StateCapability]] integration suite: a thin shell over the shared [[StateScenarios]] (the
  * calls + assertions), run against the canonical `state.redis` component via the live sidecar from
  * `scripts/js-integration-env.sh up`. The JVM twin [[StateItTest]] runs the very same scenarios — only the bring-up
  * and the `js.async{}.toFuture` boundary differ.
  *
  * Every munit body is `js.async { ... }.toFuture` — never a raw `js.Promise`, which munit would NOT await (a vacuous
  * pass).
  */
@scala.caps.assumeSafe
class StateJsIntegrationTest extends FunSuite, StateScenarios:

  override def munitTimeout: Duration = 120.seconds

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("state: save then get returns the saved value")(run(saveThenGet))
  test("state: get for a missing key returns None")(run(getMissingReturnsNone))
  test("state: getWithETag returns value and etag after save")(run(getWithETagAfterSave))
  test("state: getWithETag for a missing key returns none/none")(run(getWithETagMissingReturnsNone))
  test("state: saveWithETag succeeds with the current etag and conflicts with a stale one")(
    run(saveWithETagSucceedsThenConflicts),
  )
  test("state: delete removes a key")(run(delete))
  test("state: deleteWithETag conflicts on a stale etag then succeeds on the current one")(
    run(deleteWithETagConflictThenSucceeds),
  )
  test("state: saveBulk persists all entries and getBulk reads them (None for absent)")(run(saveBulkAndGetBulk))
  test("state: transaction upserts and deletes atomically")(run(transactionUpsertsAndDeletes))
