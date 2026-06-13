package dapr4s.test.integration

import dapr4s.*

/** Canonical Dapr component names and seeded fixtures shared by every integration suite on BOTH platforms. These match
  * scripts/it/components/<name>.yaml (the single source of truth for the component definitions) and
  * scripts/it/secrets.json. The JVM fixture ([[SharedDaprItSuite]]) and the JS env ([[JsItEnv]]) both reference these,
  * as do the shared scenario traits — so a name is declared once.
  */
object ItNames:
  val StateStore: StateStoreName = StateStoreName("statestore")
  val PubSub: PubSubName = PubSubName("pubsub")
  val LockStore: LockStoreName = LockStoreName("lockstore")
  val ConfigStore: ConfigurationStoreName = ConfigurationStoreName("configstore")
  val SecretStore: SecretStoreName = SecretStoreName("secretstore")
  val CryptoStore: CryptoComponentName = CryptoComponentName("cryptostore")
  val CryptoKey: CryptoKeyName = CryptoKeyName("rsa-key")

  /** Configuration items both harnesses seed into redis as `value||version`. */
  val ConfigKeyA: ConfigurationKey = ConfigurationKey("dapr4s-it-cfg-a")
  val ConfigKeyB: ConfigurationKey = ConfigurationKey("dapr4s-it-cfg-b")

  /** Secrets both harnesses seed via scripts/it/secrets.json. */
  val SecretKeyA: SecretKey = SecretKey("it-secret-a")
  val SecretValueA: SecretValue = SecretValue("secret-value-alpha")
  val SecretKeyB: SecretKey = SecretKey("it-secret-b")
  val SecretValueB: SecretValue = SecretValue("secret-value-beta")

  /** Monotonic, link-safe unique suffix for test resources (NOT `java.util.UUID`: it does not link on Scala.js —
    * reaches for `SecureRandom`). Uniqueness across one harness run is all that is needed; munit runs a suite's tests
    * sequentially, so a plain counter is sufficient.
    */
  private var counter: Long = 0L
  def fresh(prefix: String): String =
    counter += 1
    s"$prefix-${System.currentTimeMillis()}-$counter"
