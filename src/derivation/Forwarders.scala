package dapr4s.derivation

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration
import java.time.Instant

/** Runtime forwarders for the capability `*.derive` macros (other than Invoke,
  * which has its own [[InvokeDerivationRuntime]]).
  *
  * Each derived method expands to a single flat call into one of these methods, passing the
  * capability and any `JsonCodec`s as plain explicit arguments. Performing the capability call
  * here — in ordinary Scala — keeps generated trees trivial: no synthesised `given`s (which the
  * compiler would lift and capture into the enclosing class) and no by-hand reconstruction of
  * the capabilities' interleaved type/`using` clauses.
  */
@scala.caps.assumeSafe
object Forwarders:

  // ---- Bindings -------------------------------------------------------------

  def bindingInvoke[Req, Resp](
      cap: BindingsCapability,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
      reqCodec: JsonCodec[Req],
      respCodec: JsonCodec[Resp],
  ): Option[Resp] =
    given JsonCodec[Req]  = reqCodec
    given JsonCodec[Resp] = respCodec
    cap.invoke[Req](operation, data, metadata)[Resp]

  def bindingInvokeOneWay[Req](
      cap: BindingsCapability,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
      reqCodec: JsonCodec[Req],
  ): Unit =
    given JsonCodec[Req] = reqCodec
    cap.invokeOneWay[Req](operation, data, metadata)

  // ---- Actor ----------------------------------------------------------------

  def actorInvokeBody[Req, Resp](
      cap: ActorCapability,
      method: ActorMethodName,
      data: Req,
      reqCodec: JsonCodec[Req],
      respCodec: JsonCodec[Resp],
  ): Resp =
    given JsonCodec[Req]  = reqCodec
    given JsonCodec[Resp] = respCodec
    cap.invoke[Req](method, data)[Resp]

  def actorInvokeNoBody[Resp](cap: ActorCapability, method: ActorMethodName, respCodec: JsonCodec[Resp]): Resp =
    given JsonCodec[Resp] = respCodec
    cap.invoke[Resp](method)

  def actorInvokeVoid(cap: ActorCapability, method: ActorMethodName): Unit =
    cap.invokeVoid(method)

  def actorRegisterReminder[T](
      ctx: ActorContext,
      name: ReminderName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration],
      codec: JsonCodec[T],
  ): Unit =
    given JsonCodec[T] = codec
    ctx.registerReminder[T](name, data, dueTime, period)

  def actorRegisterTimer[T](
      ctx: ActorContext,
      name: TimerName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration],
      codec: JsonCodec[T],
  ): Unit =
    given JsonCodec[T] = codec
    ctx.registerTimer[T](name, data, dueTime, period)

  // ---- Publish ---------------------------------------------------------------

  def publish[T](cap: PublishCapability, topic: Topic, data: T, codec: JsonCodec[T]): Unit =
    given JsonCodec[T] = codec
    cap.publish[T](topic, data)

  def publishMeta[T](
      cap: PublishCapability,
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
      codec: JsonCodec[T],
  ): Unit =
    given JsonCodec[T] = codec
    cap.publishWithMetadata[T](topic, data, metadata)

  // ---- Secrets --------------------------------------------------------------

  def secretsGet(
      cap: SecretsCapability,
      key: SecretKey,
      metadata: Map[MetadataKey, MetadataValue],
  ): Option[SecretValue] =
    cap.get(key, metadata)

  // ---- Configuration --------------------------------------------------------

  def configGet(
      cap: ConfigurationCapability,
      key: ConfigurationKey,
      metadata: Map[MetadataKey, MetadataValue],
  ): Option[ConfigurationItem] =
    cap.get(Seq(key), metadata).get(key)

  // ---- Crypto ---------------------------------------------------------------

  def cryptoEncrypt(
      cap: CryptoCapability,
      keyName: CryptoKeyName,
      plaintext: ArraySeq[Byte],
      algorithm: KeyWrapAlgorithm,
  ): ArraySeq[Byte] =
    cap.encrypt(keyName, plaintext, algorithm)

  def cryptoEncryptString(
      cap: CryptoCapability,
      keyName: CryptoKeyName,
      plaintext: String,
      algorithm: KeyWrapAlgorithm,
  ): ArraySeq[Byte] =
    cap.encryptString(keyName, plaintext, algorithm)

  // ---- Jobs -----------------------------------------------------------------

  def jobSchedule[T](
      cap: JobsCapability,
      name: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[Instant],
      repeats: Option[Int],
      ttl: Option[Instant],
      codec: JsonCodec[T],
  ): Unit =
    given JsonCodec[T] = codec
    cap.schedule[T](name, data, schedule, dueTime, repeats, ttl)

  def jobScheduleOnce[T](
      cap: JobsCapability,
      name: JobName,
      data: T,
      dueTime: Instant,
      ttl: Option[Instant],
      codec: JsonCodec[T],
  ): Unit =
    given JsonCodec[T] = codec
    cap.scheduleOnce[T](name, data, dueTime, ttl)

  def jobGet(cap: JobsCapability, name: JobName): Option[JobDetails] =
    cap.get(name)

  // ---- Workflow (client) ----------------------------------------------------

  def wfStart(cap: WorkflowCapability, name: WorkflowName): WorkflowInstanceId =
    cap.start(name)

  def wfStartInput[I](cap: WorkflowCapability, name: WorkflowName, input: I, codec: JsonCodec[I]): WorkflowInstanceId =
    given JsonCodec[I] = codec
    cap.start[I](name, input)

  def wfStartWithId(cap: WorkflowCapability, name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId =
    cap.startWithId(name, instanceId)

  def wfStartWithIdInput[I](
      cap: WorkflowCapability,
      name: WorkflowName,
      instanceId: WorkflowInstanceId,
      input: I,
      codec: JsonCodec[I],
  ): WorkflowInstanceId =
    given JsonCodec[I] = codec
    cap.startWithId[I](name, instanceId, input)

  // ---- State (app-level) ----------------------------------------------------

  def stateGet[T](cap: StateCapability, key: StateStoreKey, codec: JsonCodec[T]): Option[T] =
    given JsonCodec[T] = codec
    cap.get[T](key)

  def stateSave[T](cap: StateCapability, key: StateStoreKey, value: T, codec: JsonCodec[T]): Unit =
    given JsonCodec[T] = codec
    cap.save[T](key, value)

  // ---- ActorContext (per-instance state) ------------------------------------

  def ctxGet[T](ctx: ActorContext, key: ActorStateKey, codec: JsonCodec[T]): Option[T] =
    given JsonCodec[T] = codec
    ctx.get[T](key)

  def ctxSet[T](ctx: ActorContext, key: ActorStateKey, value: T, codec: JsonCodec[T]): Unit =
    given JsonCodec[T] = codec
    ctx.set[T](key, value)

  // ---- WorkflowContext (external events) ------------------------------------

  def wfWaitEvent[T](
      ctx: WorkflowContext,
      name: EventName,
      timeout: FiniteDuration,
      codec: JsonCodec[T],
  ): Task[T]^{ctx} =
    given JsonCodec[T] = codec
    ctx.waitForExternalEvent[T](name, timeout)

  def wfWaitEventNoTimeout[T](ctx: WorkflowContext, name: EventName, codec: JsonCodec[T]): Task[T]^{ctx} =
    given JsonCodec[T] = codec
    ctx.waitForExternalEvent[T](name)

  // ---- WorkflowContext (activity scheduling, by name) -----------------------

  def callActivityByName[I, O](
      ctx: WorkflowContext,
      name: ActivityName,
      input: I,
      inputCodec: JsonCodec[I],
      outputCodec: JsonCodec[O],
  ): Task[O]^{ctx} =
    given JsonCodec[I] = inputCodec
    given JsonCodec[O] = outputCodec
    ctx.callActivityByName[I, O](name, input)

  def callActivityByNameNoInput[O](
      ctx: WorkflowContext,
      name: ActivityName,
      outputCodec: JsonCodec[O],
  ): Task[O]^{ctx} =
    given JsonCodec[O] = outputCodec
    ctx.callActivityByName[O](name)

  // ---- WorkflowActivity construction (server-side reification) ---------------

  /** Build a [[WorkflowActivity]] from a plain handler, fixing its registered name.
    *
    * The handler receives the decoded input and the runtime-supplied [[DaprCapability]] and returns the output; it is
    * a flat function so the generated derivation tree stays trivial (no synthesised `given`s, no rebinding of the
    * exclusive capability — both are passed straight through).
    */
  def workflowActivity[I, O](
      name: String,
      handler: (I, DaprCapability) => O,
      inputCodec: JsonCodec[I],
      outputCodec: JsonCodec[O],
  ): WorkflowActivity[I, O] =
    given JsonCodec[I] = inputCodec
    given JsonCodec[O] = outputCodec
    // WHY AnyRef: the handler captures the user activity instance, so storing it directly would give
    // the WorkflowActivity instance a non-empty capture set. Erase it (the same trick as
    // Subscription.rawHandler) so the activity can live in the plain DaprApp.activities list; cast
    // back here under @assumeSafe. Done in this non-safe-mode object so no asInstanceOf leaks into
    // the safe-mode call site that the derivation macro expands into.
    val raw: AnyRef = handler.asInstanceOf[AnyRef]
    new WorkflowActivity[I, O]:
      override def activityName: String              = name
      def execute(input: I)(using DaprCapability): O =
        raw.asInstanceOf[(I, DaprCapability^) => O](input, summon[DaprCapability])

  // ---- Actor route construction (server-side reification) -------------------

  def actorMethodRoute[Q, R](
      name: ActorMethodName,
      handler: Q => R,
      qCodec: JsonCodec[Q],
      rCodec: JsonCodec[R],
  ): ActorMethodRoute =
    given JsonCodec[Q] = qCodec
    given JsonCodec[R] = rCodec
    ActorMethodRoute[Q, R](name)(handler)

  def actorReminderRoute[Payload](
      name: ReminderName,
      handler: Payload => Unit,
      codec: JsonCodec[Payload],
  ): ActorReminderRoute =
    given JsonCodec[Payload] = codec
    ActorReminderRoute[Payload](name)(handler)

  def actorTimerRoute[Payload](
      name: TimerName,
      handler: Payload => Unit,
      codec: JsonCodec[Payload],
  ): ActorTimerRoute =
    given JsonCodec[Payload] = codec
    ActorTimerRoute[Payload](name)(handler)

  // ---- Invocation / subscription route construction (server-side) -----------

  def invocationRoute[Q, R](
      name: InvokeMethodName,
      handler: Q => R,
      qCodec: JsonCodec[Q],
      rCodec: JsonCodec[R],
  ): InvokeRoute =
    given JsonCodec[Q] = qCodec
    given JsonCodec[R] = rCodec
    InvokeRoute[Q, R](name)(handler)

  def subscriptionRoute[T](
      pubsubName: PubSubName,
      topic: Topic,
      deadLetter: Option[Topic],
      handler: CloudEvent[T] => SubscriptionResult,
      codec: JsonCodec[T],
  ): Subscription =
    given JsonCodec[T] = codec
    Subscription[T](pubsubName, topic, deadLetter)(handler)

  def bindingRoute[T](bindingName: BindingName, handler: T => Unit, codec: JsonCodec[T]): BindingRoute =
    given JsonCodec[T] = codec
    BindingRoute[T](bindingName)(handler)

  def jobRoute[T](name: JobName, handler: T => Unit, codec: JsonCodec[T]): JobRoute =
    given JsonCodec[T] = codec
    JobRoute[T](name)(handler)
