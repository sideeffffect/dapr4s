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
  * thrown [[DaprApiException]] on both platforms (None is returned only when the call succeeds but the response lacks
  * the key). The `errorCode` differs per transport (the JVM talks gRPC, which carries no Dapr error code for this
  * legacy error; Scala.js talks HTTP and gets `ERR_SECRET_GET`), so only the type is asserted.
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite:

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws ComponentNotFoundException(Secrets)")(
    withDapr(getFromUnknownStoreThrows),
  )

  def getSeededReturnsSome(using DaprCapability): Unit =
    DaprCapability.secrets(ItNames.SecretStore):
      assertEquals(SecretsCapability.Instance.get(ItNames.SecretKeyA), Some(ItNames.SecretValueA))
      assertEquals(SecretsCapability.Instance.get(ItNames.SecretKeyB), Some(ItNames.SecretValueB))

  def getBulkContainsSeeded(using DaprCapability): Unit =
    DaprCapability.secrets(ItNames.SecretStore):
      val bulk = SecretsCapability.Instance.getBulk()
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
      intercept[DaprApiException](SecretsCapability.Instance.get(SecretKey(ItNames.fresh("absent")))): Unit

  def getFromUnknownStoreThrows(using DaprCapability): Unit =
    DaprCapability.secrets(SecretStoreName("nonexistent-store")):
      ItErrors.assertComponentNotFound(DaprComponentKind.Secrets, "nonexistent-store")(
        SecretsCapability.Instance.get(SecretKey("any-key")),
      )
