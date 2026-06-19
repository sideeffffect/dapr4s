package dapr4s.test.unit

import dapr4s.*
import dapr4s.derivation.*
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

// Recording fakes + derive traits for CapabilityDerivationTest. Unused capability methods are
// stubbed with `???` (never called by the derived facades under test).

// ---- Bindings ---------------------------------------------------------------

trait BindingClient:
  def create(req: Req)(using BindingsCapability, JsonCodec[Req]): Unit
  def query(req: Req)(using BindingsCapability, JsonCodec[Req], JsonCodec[Resp]): Option[Resp]
lazy val BindingClient: BindingClient = Bindings.derive[BindingClient]

@scala.caps.assumeSafe
final class FakeBindings(resp: String) extends BindingsCapability:
  val log: mutable.ListBuffer[String]      = mutable.ListBuffer.empty
  val bindingName: BindingName             = BindingName("b")
  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req, metadata: Map[MetadataKey, MetadataValue])[
      Resp: JsonCodec,
  ]: Option[Resp] =
    log += s"invoke|${operation.value}|${summon[JsonCodec[Req]].encode(data)}|${metadata.size}"
    summon[JsonCodec[Resp]].decode(resp).toOption
  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req, metadata: Map[MetadataKey, MetadataValue]): Unit =
    log += s"oneWay|${operation.value}|${summon[JsonCodec[Req]].encode(data)}|${metadata.size}"

// ---- Actor ------------------------------------------------------------------

trait ActorClient:
  def increment(req: Req)(using ActorCapability, JsonCodec[Req], JsonCodec[Resp]): Resp
  def get()(using ActorCapability, JsonCodec[Resp]): Resp
  def reset()(using ActorCapability): Unit
lazy val ActorClient: ActorClient = Actor.derive[ActorClient]

@scala.caps.assumeSafe
final class FakeActor(resp: String) extends ActorCapability:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  val actorType: ActorType            = ActorType("A")
  val actorId: ActorId                = ActorId("1")
  def invoke[Req: JsonCodec](method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp =
    log += s"invokeBody|${method.value}|${summon[JsonCodec[Req]].encode(data)}"
    JsonCodec.decodeOrThrow[Resp](resp)
  def invoke[Resp: JsonCodec](method: ActorMethodName): Resp =
    log += s"invoke|${method.value}"
    JsonCodec.decodeOrThrow[Resp](resp)
  def invokeVoid(method: ActorMethodName): Unit =
    log += s"void|${method.value}"

// ---- Publish -----------------------------------------------------------------

trait Publisher:
  def orders(event: Req)(using PublishCapability, JsonCodec[Req]): Unit
  def audit(event: Req, metadata: Map[MetadataKey, MetadataValue])(using PublishCapability, JsonCodec[Req]): Unit
lazy val Publisher: Publisher = Publish.derive[Publisher]

@scala.caps.assumeSafe
final class FakePubSub extends PublishCapability:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  val pubsubName: PubSubName           = PubSubName("ps")
  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    log += s"publish|${topic.value}|${summon[JsonCodec[T]].encode(data)}"
  def publishWithMetadata[T: JsonCodec](topic: Topic, data: T, metadata: Map[MetadataKey, MetadataValue]): Unit =
    log += s"publishMeta|${topic.value}|${summon[JsonCodec[T]].encode(data)}|${metadata.size}"
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult = ???

// ---- Secrets ----------------------------------------------------------------

trait SecretClient:
  @name("db-password") def dbPassword()(using SecretsCapability): Option[SecretValue]
lazy val SecretClient: SecretClient = Secrets.derive[SecretClient]

@scala.caps.assumeSafe
final class FakeSecrets extends SecretsCapability:
  val log: mutable.ListBuffer[String]  = mutable.ListBuffer.empty
  val storeName: SecretStoreName       = SecretStoreName("s")
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue]): Option[SecretValue] =
    log += s"get|${key.value}|${metadata.size}"
    Some(SecretValue("sealed"))
  def getBulk(metadata: Map[MetadataKey, MetadataValue]): Map[SecretKey, SecretValue] = ???

// ---- Configuration ----------------------------------------------------------

trait ConfigurationClient:
  @name("feature-x") def featureX()(using ConfigurationCapability): Option[ConfigurationItem]
lazy val ConfigurationClient: ConfigurationClient = Configuration.derive[ConfigurationClient]

@scala.caps.assumeSafe
final class FakeConfig extends ConfigurationCapability:
  val log: mutable.ListBuffer[String]  = mutable.ListBuffer.empty
  val storeName: ConfigurationStoreName       = ConfigurationStoreName("c")
  def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue]): Map[ConfigurationKey, ConfigurationItem] =
    log += s"get|${keys.map(_.value).mkString(",")}|${metadata.size}"
    keys.map(k => k -> ConfigurationItem(k, ConfigurationValue("v"), ConfigurationVersion(""), Map.empty)).toMap
  def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue])(
      onChange: ConfigurationUpdate => Unit,
  ): AutoCloseable^{this} = ???

// ---- Crypto -----------------------------------------------------------------

trait CryptoClient:
  def rawKey(plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm)(using CryptoCapability): ArraySeq[Byte]
  @name("text-key") def textKey(plaintext: String, algorithm: KeyWrapAlgorithm)(using CryptoCapability): ArraySeq[Byte]
lazy val CryptoClient: CryptoClient = Crypto.derive[CryptoClient]

@scala.caps.assumeSafe
final class FakeCrypto extends CryptoCapability:
  val log: mutable.ListBuffer[String]      = mutable.ListBuffer.empty
  val componentName: CryptoComponentName   = CryptoComponentName("crypto")
  def encrypt(keyName: CryptoKeyName, plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm): ArraySeq[Byte] =
    log += s"encrypt|${keyName.value}|${plaintext.length}|${algorithm.value}"
    ArraySeq.from("ct".getBytes)
  def decrypt(ciphertext: ArraySeq[Byte]): ArraySeq[Byte] = ???

// ---- Jobs: JVM-only (the Dapr JS SDK has no jobs API) — see JvmCapabilityDerivationFixtures.

// ---- Workflow (client) ------------------------------------------------------

trait WorkflowClient:
  def order()(using AccessWorkflowCapability): WorkflowInstanceId
  def orderInput(input: Req)(using AccessWorkflowCapability, JsonCodec[Req]): WorkflowInstanceId
  def orderWithId(instanceId: WorkflowInstanceId)(using AccessWorkflowCapability): WorkflowInstanceId
  def orderFull(instanceId: WorkflowInstanceId, input: Req)(using
      AccessWorkflowCapability,
      JsonCodec[Req],
  ): WorkflowInstanceId
lazy val WorkflowClient: WorkflowClient = Workflow.derive[WorkflowClient]

@scala.caps.assumeSafe
final class FakeWorkflow extends AccessWorkflowCapability:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  def start(name: WorkflowName): WorkflowInstanceId =
    log += s"start|${name.value}"; WorkflowInstanceId("wf")
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId =
    log += s"startInput|${name.value}|${summon[JsonCodec[I]].encode(input)}"; WorkflowInstanceId("wf")
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId =
    log += s"startWithId|${name.value}|${instanceId.value}"; instanceId
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId =
    log += s"startWithIdInput|${name.value}|${instanceId.value}|${summon[JsonCodec[I]].encode(input)}"; instanceId
  // The derived facades under test only call the launch ops; per-instance ops are reached via apply(id).
  def apply(instanceId: WorkflowInstanceId): WorkflowInstanceCapability^{this} = ???

// ---- State (app-level) ------------------------------------------------------

trait StateClient:
  def counter(using StateCapability, JsonCodec[Int]): Option[Int]
  def counter_=(value: Int)(using StateCapability, JsonCodec[Int]): Unit
lazy val StateClient: StateClient = State.derive[StateClient]

@scala.caps.assumeSafe
final class FakeState extends StateCapability:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  val storeName: StateStoreName       = StateStoreName("st")
  def get[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency): Option[T] =
    log += s"get|${key.value}"; summon[JsonCodec[T]].decode("41").toOption
  def save[T: JsonCodec](key: StateStoreKey, value: T): Unit =
    log += s"save|${key.value}|${summon[JsonCodec[T]].encode(value)}"
  def getWithETag[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency): StateEntry[T]      = ???
  def getBulk[T: JsonCodec](keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]]               = ???
  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit                                   = ???
  def saveWithETag[T: JsonCodec](
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] = ???
  def delete(key: StateStoreKey): Unit = ???
  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] = ???
  def transaction(ops: Seq[StateOp]): Unit                            = ???
  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] = ???

// ---- ActorContext (per-instance state) --------------------------------------

trait ActorStateClient:
  def count(using ActorContext, JsonCodec[Int]): Option[Int]
  def count_=(value: Int)(using ActorContext, JsonCodec[Int]): Unit
lazy val ActorStateClient: ActorStateClient = ActorState.derive[ActorStateClient]

@scala.caps.assumeSafe
final class FakeActorContext extends ActorContext:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  def get[T: JsonCodec](key: ActorStateKey): Option[T] =
    log += s"get|${key.value}"; summon[JsonCodec[T]].decode("7").toOption
  def set[T: JsonCodec](key: ActorStateKey, value: T): Unit =
    log += s"set|${key.value}|${summon[JsonCodec[T]].encode(value)}"
  def remove(key: ActorStateKey): Unit = ???
  def registerReminder[T: JsonCodec](name: ReminderName, data: T, dueTime: FiniteDuration, period: Option[FiniteDuration]): Unit =
    log += s"reminder|${name.value}|${summon[JsonCodec[T]].encode(data)}|$dueTime|$period"
  def unregisterReminder(name: ReminderName): Unit = ???
  def registerTimer[T: JsonCodec](name: TimerName, data: T, dueTime: FiniteDuration, period: Option[FiniteDuration]): Unit =
    log += s"timer|${name.value}|${summon[JsonCodec[T]].encode(data)}|$dueTime|$period"
  def unregisterTimer(name: TimerName): Unit = ???
