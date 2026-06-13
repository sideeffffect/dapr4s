//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[dapr4s.StateCapability]] integration suite: a thin shell over the shared [[StateScenarios]] (the calls +
  * assertions) and [[SharedDaprItSuite]] (the testcontainers sidecar on the canonical `state.redis` component). The JS
  * twin [[StateJsIntegrationTest]] runs the very same scenarios.
  *
  * Replaces the former StateCapabilityServerTest (server-routed) + StateIntegrationTest (direct): route dispatch is
  * covered by the unit ServerRouteDerivationTest; this exercises the capability directly against a real sidecar,
  * identically to JS.
  */
@scala.caps.assumeSafe
class StateItTest extends FunSuite, SharedDaprItSuite, StateScenarios:

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
