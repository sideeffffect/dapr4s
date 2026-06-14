//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Scala.js (Wasm+JSPI) [[StateCapability]] integration suite: a thin shell over the shared [[StateScenarios]] (the
  * calls + assertions), run against the canonical `state.redis` component via a sidecar started in-process by
  * [[SharedDaprJsItSuite]] (the JS twin of the JVM `SharedDaprItSuite`). The JVM twin [[StateItTest]] runs the very
  * same scenarios — only bring-up and the `withDapr` (`js.async{}.toFuture`) boundary differ.
  */
@scala.caps.assumeSafe
class StateJsIntegrationTest extends FunSuite, StateScenarios, SharedDaprJsItSuite:

  test("state: save then get returns the saved value")(withDapr(saveThenGet))
  test("state: get for a missing key returns None")(withDapr(getMissingReturnsNone))
  test("state: getWithETag returns value and etag after save")(withDapr(getWithETagAfterSave))
  test("state: getWithETag for a missing key returns none/none")(withDapr(getWithETagMissingReturnsNone))
  test("state: saveWithETag succeeds with the current etag and conflicts with a stale one")(
    withDapr(saveWithETagSucceedsThenConflicts),
  )
  test("state: delete removes a key")(withDapr(delete))
  test("state: deleteWithETag conflicts on a stale etag then succeeds on the current one")(
    withDapr(deleteWithETagConflictThenSucceeds),
  )
  test("state: saveBulk persists all entries and getBulk reads them (None for absent)")(withDapr(saveBulkAndGetBulk))
  test("state: transaction upserts and deletes atomically")(withDapr(transactionUpsertsAndDeletes))
