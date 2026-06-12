package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.Assertions
import unsafeExceptions.canThrowAny

/** Direct-call [[StateCapability]] scenarios shared by the JVM and JS integration suites. Each method performs the
  * capability calls and the munit assertions; the per-platform suite owns only bring-up and the sync/`Future` boundary
  * (see [[SharedDaprItSuite]] on JVM, the `js.async{}.toFuture` shells on JS). All run against the canonical
  * `statestore` (state.redis) on both platforms.
  *
  * Keys are unique per call ([[ItNames.fresh]]) so scenarios sharing one sidecar do not interfere.
  */
trait StateScenarios:
  self: Assertions =>

  def saveThenGet(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val k = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(k, "hello")
      assertEquals(StateCapability.get[String](k), Some("hello"))

  def getMissingReturnsNone(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      assertEquals(StateCapability.get[String](StateStoreKey(ItNames.fresh("absent"))), None)

  def getWithETagAfterSave(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val k = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(k, "world")
      val e = StateCapability.getWithETag[String](k)
      assertEquals(e.value, Some("world"))
      assert(e.etag.isDefined, "ETag should be present after save")

  def getWithETagMissingReturnsNone(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val e = StateCapability.getWithETag[String](StateStoreKey(ItNames.fresh("absent")))
      assertEquals(e.value, None)
      assertEquals(e.etag, None)

  def saveWithETagSucceedsThenConflicts(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val k = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(k, "v1")
      val etag = StateCapability.getWithETag[String](k).etag.getOrElse(fail("expected an etag after save"))
      assertEquals(StateCapability.saveWithETag(k, "v2", etag), None, "save with the current etag should succeed")
      // The successful save bumped the server-side etag, so `etag` is now STALE — a genuine
      // optimistic-concurrency conflict. A fabricated string would NOT do: Redis etags are
      // integers, and daprd rejects a non-numeric etag with 400 (invalid etag) rather than a
      // conflict — the exact reason both platforms reuse the stale-but-real etag here.
      assert(
        StateCapability.saveWithETag(k, "v3", etag).isDefined,
        "save with a stale etag should report a conflict",
      )
      assertEquals(StateCapability.get[String](k), Some("v2"))

  def delete(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val k = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(k, "bye")
      StateCapability.delete(k)
      assertEquals(StateCapability.get[String](k), None)

  def deleteWithETagConflictThenSucceeds(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val k = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(k, "x")
      val stale = StateCapability.getWithETag[String](k).etag.getOrElse(fail("expected an etag"))
      StateCapability.save(k, "x2") // bumps the server-side etag, so `stale` is now outdated
      // Stale-but-real etag → genuine conflict (see saveWithETagSucceedsThenConflicts on why a
      // fabricated string is wrong for Redis).
      assert(StateCapability.deleteWithETag(k, stale).isDefined, "delete with a stale etag should conflict")
      assert(StateCapability.get[String](k).isDefined, "key should survive a conflicting delete")
      val current = StateCapability.getWithETag[String](k).etag.getOrElse(fail("expected an etag"))
      assertEquals(StateCapability.deleteWithETag(k, current), None, "delete with the current etag should succeed")
      assertEquals(StateCapability.get[String](k), None)

  def saveBulkAndGetBulk(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val ka = StateStoreKey(ItNames.fresh("k"))
      val kb = StateStoreKey(ItNames.fresh("k"))
      val absent = StateStoreKey(ItNames.fresh("absent"))
      StateCapability.saveBulk[String](List(ka -> "alpha", kb -> "beta"))
      val results = StateCapability.getBulk[String](Seq(ka, kb, absent))
      assertEquals(results.get(ka).flatMap(_.value), Some("alpha"))
      assertEquals(results.get(kb).flatMap(_.value), Some("beta"))
      assertEquals(results.get(absent).flatMap(_.value), None)

  def transactionUpsertsAndDeletes(using DaprCapability): Unit =
    DaprCapability.state(ItNames.StateStore):
      val kAdd = StateStoreKey(ItNames.fresh("k"))
      val kDel = StateStoreKey(ItNames.fresh("k"))
      StateCapability.save(kDel, "gone")
      StateCapability.transaction(
        Seq(StateOp.UpsertOp[String](kAdd, "new"), StateOp.DeleteOp(kDel)),
      )
      assertEquals(StateCapability.get[String](kAdd), Some("new"))
      assertEquals(StateCapability.get[String](kDel), None)
