package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.SecretsCapability]] integration suite — a SINGLE cross-platform file, run via the per-platform
  * [[SharedDaprItSuite]] bring-up against the canonical `secretstores.local.file` store seeded from
  * scripts/it/secrets.json.
  *
  * A missing key THROWS rather than returning `None`: the local-file store answers 500, which the impl surfaces as a
  * thrown error on both platforms (None is returned only when the call succeeds but the response lacks the key). The
  * exception type differs per platform, so it is checked structurally via `Try(...).isFailure`.
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite:

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(withDapr(getFromUnknownStoreThrows))

  def getSeededReturnsSome(using DaprCapability): Unit =
    DaprCapability.secrets(ItNames.SecretStore):
      assertEquals(SecretsCapability.get(ItNames.SecretKeyA), Some(ItNames.SecretValueA))
      assertEquals(SecretsCapability.get(ItNames.SecretKeyB), Some(ItNames.SecretValueB))

  def getBulkContainsSeeded(using DaprCapability): Unit =
    DaprCapability.secrets(ItNames.SecretStore):
      val bulk = SecretsCapability.getBulk()
      // local.file getBulk nests {secretName: {key: value}}; dapr4s flattens to compound keys.
      assert(
        bulk.exists((k, v) => k.value.contains(ItNames.SecretKeyA.value) && v == ItNames.SecretValueA),
        s"expected ${ItNames.SecretKeyA.value} in bulk; got keys: ${bulk.keys.map(_.value).toList.sorted}",
      )
      assert(
        bulk.exists((k, v) => k.value.contains(ItNames.SecretKeyB.value) && v == ItNames.SecretValueB),
        s"expected ${ItNames.SecretKeyB.value} in bulk",
      )

  def getMissingKeyThrows(using DaprCapability): Unit =
    DaprCapability.secrets(ItNames.SecretStore):
      val attempt = scala.util.Try(SecretsCapability.get(SecretKey(ItNames.fresh("absent"))))
      assert(attempt.isFailure, s"expected a missing secret to throw, got: $attempt")

  def getFromUnknownStoreThrows(using DaprCapability): Unit =
    DaprCapability.secrets(SecretStoreName("nonexistent-store")):
      val attempt = scala.util.Try(SecretsCapability.get(SecretKey("any-key")))
      assert(attempt.isFailure, s"expected an unknown store to throw, got: $attempt")
