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
    val client = BindingClient.derive
    client.create(Req(1))
    assertEquals(client.query(Req(2)), Some(Resp("done")))
    assertEquals(fake.log.toList, List("oneWay|create|1|0", "invoke|query|2|0"))

  test("Actor: invoke body, invoke no-body, invokeVoid"):
    val fake = FakeActor("res")
    given ActorCapability = fake
    val client = ActorClient.derive
    assertEquals(client.increment(Req(5)), Resp("res"))
    assertEquals(client.get(), Resp("res"))
    client.reset()
    assertEquals(fake.log.toList, List("invokeBody|increment|5", "invoke|get", "void|reset"))

  test("PubSub: publish and publishWithMetadata"):
    val fake = FakePubSub()
    given PubSubCapability = fake
    val client = Publisher.derive
    client.orders(Req(1))
    client.audit(Req(2), Map(MetadataKey("k") -> MetadataValue("v")))
    assertEquals(fake.log.toList, List("publish|orders|1", "publishMeta|audit|2|1"))

  test("Secrets: get with @name key"):
    val fake = FakeSecrets()
    given SecretsCapability = fake
    val client = SecretClient.derive
    assertEquals(client.dbPassword(), Some(SecretValue("sealed")))
    assertEquals(fake.log.toList, List("get|db-password|0"))

  test("Configuration: single-key get returns Option[ConfigItem]"):
    val fake = FakeConfig()
    given ConfigurationCapability = fake
    val client = ConfigClient.derive
    assertEquals(client.featureX().map(_.value), Some(ConfigValue("v")))
    assertEquals(fake.log.toList, List("get|feature-x|0"))

  test("Crypto: encrypt (bytes) and encryptString (String)"):
    val fake = FakeCrypto()
    given CryptoCapability = fake
    val client = CryptoClient.derive
    client.rawKey(ArraySeq.from("hi".getBytes), KeyWrapAlgorithm("RSA"))
    client.textKey("hello", KeyWrapAlgorithm("AES"))
    assertEquals(fake.log.toList, List("encrypt|rawKey|2|RSA", "encrypt|text-key|5|AES"))

  test("Jobs: schedule, scheduleOnce, get"):
    val fake = FakeJobs()
    given JobsCapability = fake
    val client = JobClient.derive
    client.recur(Req(1), JobSchedule.Every(scala.concurrent.duration.DurationInt(5).seconds))
    client.once(Req(2), java.time.Instant.EPOCH)
    client.fetch()
    assertEquals(fake.log.toList, List("schedule|recur|1", "once|once|2", "get|recur"))

  test("Workflow: start, startInput, startWithId, startWithIdInput"):
    val fake = FakeWorkflow()
    given WorkflowCapability = fake
    val client = WorkflowClient.derive
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
    val client = StateClient.derive
    client.counter = 9
    assertEquals(client.counter, Some(41))
    assertEquals(fake.log.toList, List("save|counter|9", "get|counter"))

  test("ActorState: getter/setter map to ctx get/set on ActorStateKey"):
    val fake = FakeActorContext()
    given ActorContext = fake
    val client = ActorStateClient.derive
    client.count = 3
    assertEquals(client.count, Some(7))
    assertEquals(fake.log.toList, List("set|count|3", "get|count"))
