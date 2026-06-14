package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.StateCapability]] integration suite — a SINGLE cross-platform file. The calls + assertions come from the
  * shared [[StateScenarios]]; the bring-up comes from [[SharedDaprItSuite]], a trait with one implementation per
  * platform under the same name (testcontainers-java on the JVM, `@dapr/testcontainer-node` on Scala.js), so each
  * platform build links its own and this one file is the suite on both. `withDapr` runs each scenario in a
  * [[dapr4s.DaprCapability]] scope (synchronous on the JVM, `Future`-returning on JS — both accepted by munit).
  *
  * Route dispatch is covered by the unit ServerRouteDerivationTest; this exercises the capability directly against a
  * real sidecar on the canonical `state.redis` component.
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
