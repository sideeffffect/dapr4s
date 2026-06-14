package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Cross-platform munit test registrations for the integration suites whose JVM and JS runs exercise the very same
  * shared scenarios. Each `*SuiteDef` mixes in the scenario trait (the calls + assertions) and registers the
  * `test(...)` lines once; the matching concrete suite class (e.g. [[StateItTest]]) lives in test/shared too and just
  * mixes a `*SuiteDef` with a per-platform bring-up trait (`SharedDaprItSuite` / `InvokeHarness`) that supplies
  * [[DaprItFixture.withDapr]] (synchronous on the JVM, `Future`-returning on JS).
  *
  * These cover the five direct-call capability suites (State / Configuration / Crypto / Lock / Secrets) plus Invoke
  * (whose `serverAppId` / `retrying` hooks stay abstract for the platform `InvokeHarness` to fill in). The Actor /
  * PubSub / Workflow server-delivery suites are NOT shared: their JVM and JS twins exercise genuinely different bodies
  * (the JVM ones dispatch over HTTP and assert far more), so there is no common scenario set to register.
  *
  * WHY @assumeSafe: the registrations eta-expand the scenario methods into `DaprCapability ?=> Unit` closures passed to
  * `withDapr` — the same capture-checking erasure the scenario traits and bring-up fixtures already assume.
  */
@scala.caps.assumeSafe
trait StateSuiteDef extends StateScenarios:
  self: FunSuite & DaprItFixture =>

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

@scala.caps.assumeSafe
trait ConfigurationSuiteDef extends ConfigurationScenarios:
  self: FunSuite & DaprItFixture =>

  test("configuration: get returns the seeded items with values and versions")(withDapr(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(withDapr(getUnknownKeyReturnsNoItem))

@scala.caps.assumeSafe
trait CryptoSuiteDef extends CryptoScenarios:
  self: FunSuite & DaprItFixture =>

  test("crypto: encryptString then decryptString round-trips the original text")(
    withDapr(encryptDecryptStringRoundTrip),
  )
  test("crypto: encrypt then decrypt round-trips raw bytes")(withDapr(encryptDecryptBytesRoundTrip))

@scala.caps.assumeSafe
trait LockSuiteDef extends LockScenarios:
  self: FunSuite & DaprItFixture =>

  test("lock: tryLock on a free resource returns true")(withDapr(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(withDapr(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(
    withDapr(unlockByOwnerThenLockNotFound),
  )

@scala.caps.assumeSafe
trait SecretsSuiteDef extends SecretsScenarios:
  self: FunSuite & DaprItFixture =>

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(withDapr(getFromUnknownStoreThrows))

/** Invoke registrations. `serverAppId` and `retrying` (declared abstract by [[InvokeScenarios]]) stay abstract here —
  * the platform class fills them in, because the server bring-up genuinely differs (the JVM polls sidecar health up
  * front and supplies identity `retrying`; JS supplies `retryUntilSuccess`).
  */
@scala.caps.assumeSafe
trait InvokeSuiteDef extends InvokeScenarios:
  self: FunSuite & DaprItFixture =>

  test("invoke: echo roundtrip")(withDapr(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler")(withDapr(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(withDapr(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws")(withDapr(nonexistentAppThrows))
