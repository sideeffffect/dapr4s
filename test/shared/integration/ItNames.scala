package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*

/** Canonical Dapr component names, manifest references and seeded fixtures shared by every integration suite on BOTH
  * platforms. These match scripts/it/components/<name>.yaml (the single source of truth for the component definitions)
  * and scripts/it/secrets.json. The JVM fixtures ([[SharedDaprItSuite]] / [[JvmItComponents]]) and the JS ones
  * ([[JsItComponents]] / [[JsItEnv]]) all reference these, as do the shared scenario traits — so each fact is declared
  * once.
  */
object ItNames:
  /** The app id every server-delivery suite registers its routes under (what `InvokeItTest` targets); the daprd
    * container's `--app-id` is set to this on both platforms.
    */
  val ServerAppId: AppId = AppId("it-server")

  val StateStore: StateStoreName = StateStoreName("statestore")
  val PubSub: PubSubName = PubSubName("pubsub")
  val LockStore: LockStoreName = LockStoreName("lockstore")
  val ConfigStore: ConfigurationStoreName = ConfigurationStoreName("configstore")
  val SecretStore: SecretStoreName = SecretStoreName("secretstore")
  val CryptoStore: CryptoComponentName = CryptoComponentName("cryptostore")
  val CryptoKey: CryptoKeyName = CryptoKeyName("rsa-key")

  /** Canonical component manifest file names (= scripts/it/components/<name>.yaml), in the order both harnesses render
    * them into the sidecar.
    */
  val ComponentFileNames: List[String] =
    List("statestore", "pubsub", "lockstore", "configstore", "cryptostore", "secretstore").map(_ + ".yaml")

  /** Network alias of the redis container both harnesses start on the shared Docker network; the rendered redisHost is
    * `redis:6379`.
    */
  val RedisAlias = "redis"
  val RedisHostValue = s"$RedisAlias:6379"

  /** The placeholder both component renderers substitute with [[RedisHostValue]] (= scripts/it/render-components.sh).
    */
  val Placeholder = "${DAPR4S_IT_REDIS_HOST}"

  /** Configuration items both harnesses seed into redis as `value||version`. */
  val ConfigKeyA: ConfigurationKey = ConfigurationKey("dapr4s-it-cfg-a")
  val ConfigKeyB: ConfigurationKey = ConfigurationKey("dapr4s-it-cfg-b")

  /** The `value||version` pairs both harnesses seed into redis for [[ConfigKeyA]] / [[ConfigKeyB]] — the redis config
    * store splits each on `||`. Keys are derived from the canonical [[ConfigKeyA]] / [[ConfigKeyB]] so they cannot
    * drift.
    */
  val SeededConfig: List[(String, String)] =
    List(ConfigKeyA.value -> "alpha||v1", ConfigKeyB.value -> "beta||v2")

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
