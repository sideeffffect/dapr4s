package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.collection.immutable.ArraySeq

@scala.caps.assumeSafe
class CapabilityDerivationTest extends FunSuite:

  test("Bindings: invokeOneWay (Unit) and invoke (Option[Resp])"):
    val fake = FakeBindings("done")
    given BindingsCapability = fake
    val client = BindingClient
    client.create(Req(1))
    assertEquals(client.query(Req(2)), Some(Resp("done")))
    assertEquals(fake.log.toList, List("oneWay|create|1|0", "invoke|query|2|0"))

  test("Actor: invoke body, invoke no-body, invokeVoid"):
    val fake = FakeActor("res")
    given ActorCapability = fake
    val client = ActorClient
    assertEquals(client.increment(Req(5)), Resp("res"))
    assertEquals(client.get(), Resp("res"))
    client.reset()
    assertEquals(fake.log.toList, List("invokeBody|increment|5", "invoke|get", "void|reset"))

  test("Publish: publish and publishWithMetadata"):
    val fake = FakePubSub()
    given PublishCapability = fake
    val client = Publisher
    client.orders(Req(1))
    client.audit(Req(2), Map(MetadataKey("k") -> MetadataValue("v")))
    assertEquals(fake.log.toList, List("publish|orders|1", "publishMeta|audit|2|1"))

  test("Secrets: get with @name key"):
    val fake = FakeSecrets()
    given SecretsCapability = fake
    val client = SecretClient
    assertEquals(client.dbPassword(), Some(SecretValue("sealed")))
    assertEquals(fake.log.toList, List("get|db-password|0"))

  test("Configuration: single-key get returns Option[ConfigurationItem]"):
    val fake = FakeConfig()
    given ConfigurationCapability = fake
    val client = ConfigurationClient
    assertEquals(client.featureX().map(_.value), Some(ConfigurationValue("v")))
    assertEquals(fake.log.toList, List("get|feature-x|0"))

  test("Crypto: encrypt (bytes) and encryptString (String)"):
    val fake = FakeCrypto()
    given CryptoCapability = fake
    val client = CryptoClient
    client.rawKey(ArraySeq.from("hi".getBytes), KeyWrapAlgorithm("RSA"))
    client.textKey("hello", KeyWrapAlgorithm("AES"))
    assertEquals(fake.log.toList, List("encrypt|rawKey|2|RSA", "encrypt|text-key|5|AES"))

  // Jobs.derive is JVM-only (the Dapr JS SDK has no jobs API) — see JvmCapabilityDerivationTest.

  test("Workflow: start, startInput, startWithId, startWithIdInput"):
    val fake = FakeWorkflow()
    given WorkflowCapability = fake
    val client = WorkflowClient
    client.order()
    client.orderInput(Req(1))
    client.orderWithId(WorkflowInstanceId("id1"))
    client.orderFull(WorkflowInstanceId("id2"), Req(2))
    assertEquals(
      fake.log.toList,
      List("start|order", "startInput|orderInput|1", "startWithId|orderWithId|id1", "startWithIdInput|orderFull|id2|2"),
    )

  test("State: getter/setter map to get/save on StateStoreKey"):
    val fake = FakeState()
    given StateCapability = fake
    val client = StateClient
    client.counter = 9
    assertEquals(client.counter, Some(41))
    assertEquals(fake.log.toList, List("save|counter|9", "get|counter"))

  test("ActorState: getter/setter map to ctx get/set on ActorStateKey"):
    val fake = FakeActorContext()
    given ActorContext = fake
    val client = ActorStateClient
    client.count = 3
    assertEquals(client.count, Some(7))
    assertEquals(fake.log.toList, List("set|count|3", "get|count"))
