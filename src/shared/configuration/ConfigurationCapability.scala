package dapr4s.configuration

import dapr4s.*

/** Universal capability for DAPR configuration: reaches **any** configuration store, so every operation names its
  * store.
  *
  * Obtained via [[DaprCapability.configuration]]. The capability family has four interfaces, all in this companion:
  *
  *   - [[ConfigurationCapability]] — any store, full authority (this trait; adds `subscribe`)
  *   - [[ConfigurationCapability.ReadOnly]] — any store, point-in-time reads only (`get`)
  *   - [[ConfigurationCapability.Instance]] — one store, full authority
  *   - [[ConfigurationCapability.Instance.ReadOnly]] — one store, point-in-time reads only
  *
  * Narrow by store with [[instance]] (`configuration.instance(storeName)`), and by authority by upcasting to the `ReadOnly`
  * super-trait. The two narrowings commute: `instance` on a [[ConfigurationCapability.ReadOnly]] yields a
  * [[ConfigurationCapability.Instance.ReadOnly]]. `subscribe` is withheld from the read-only tier because it opens a
  * long-lived outbound stream and runs a callback on a background thread — more than a plain read.
  */
@scala.caps.assumeSafe
trait ConfigurationCapability extends ConfigurationCapability.ReadOnly:
  /** Narrow to the named configuration store, keeping full authority. */
  override def instance(storeName: ConfigurationStoreName): ConfigurationCapability.Instance^{this}

  /** Subscribe to live configuration changes; see [[ConfigurationCapability.Instance.subscribe]]. */
  def subscribe(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(onChange: ConfigurationUpdate => Unit): AutoCloseable^{this}

/** The [[ConfigurationCapability]] family: the read-only universal view, the store-bound instance views, and the
  * companion-object forwarders for each.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def featureFlag()(using ConfigurationCapability.Instance.ReadOnly): Boolean =
  *     ConfigurationCapability.Instance.ReadOnly.get(Seq(ConfigurationKey("feature-x")))
  *       .get(ConfigurationKey("feature-x")).exists(_.value.value == "true")
  * }}}
  */
@scala.caps.assumeSafe
object ConfigurationCapability:

  /** Read-only view of [[ConfigurationCapability]]: reaches any store, but only reads. */
  @scala.caps.assumeSafe
  trait ReadOnly extends scala.caps.ExclusiveCapability:
    /** Narrow to the named configuration store, keeping read-only authority. */
    def instance(storeName: ConfigurationStoreName): Instance.ReadOnly^{this}

    /** Retrieve one or more configuration items by key; see [[ConfigurationCapability.Instance.ReadOnly.get]]. */
    def get(
        storeName: ConfigurationStoreName,
        keys: Seq[ConfigurationKey],
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    ): Map[ConfigurationKey, ConfigurationItem]

  /** Forwarders for [[ConfigurationCapability.ReadOnly]]. */
  @scala.caps.assumeSafe
  object ReadOnly:
    /** Narrow the read-only capability in scope to one store and return it. */
    def instance(storeName: ConfigurationStoreName)(using cap: ReadOnly): Instance.ReadOnly^{cap} =
      cap.instance(storeName)
    def get(
        storeName: ConfigurationStoreName,
        keys: Seq[ConfigurationKey],
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )(using cap: ReadOnly): Map[ConfigurationKey, ConfigurationItem] =
      cap.get(storeName, keys, metadata)

  /** Capability bound to one named configuration store, full authority. Obtained via `configuration.instance(storeName)`. */
  @scala.caps.assumeSafe
  trait Instance extends Instance.ReadOnly:
    /** Subscribe to live configuration changes for the given keys.
      *
      * `onChange` is called on a background thread whenever the sidecar delivers an update. Returns an `AutoCloseable`
      * that stops the subscription when closed. The subscription is also stopped when the enclosing [[DaprCapability]] is
      * closed.
      *
      * The returned handle captures this capability (`AutoCloseable^{this}`), so capture checking forbids it from
      * outliving the configuration scope — it cannot be stored in an outer `var` or returned and closed later, when the
      * underlying subscription is already gone. Close it within the scope (or let the scope close it).
      *
      * '''Why a capability with a callback, not a [[DaprApp]] route?''' The `onChange` callback makes this look like an
      * inbound handler (DAPR pushing data into the app), which would suggest it belongs alongside [[dapr4s.publish.Subscription]],
      * [[dapr4s.jobs.JobRoute]], and the actor reminder/timer routes in [[DaprApp]]. It does not, because those routes and this
      * subscription differ on the axis that actually separates the two layers — connection initiation and lifecycle, not
      * data direction:
      *
      *   - '''[[DaprApp]] routes''' are opened by the sidecar (the sidecar is the client, the app is the HTTP server) and
      *     must be declared '''statically at startup''' so the sidecar can enumerate them (e.g. via `/dapr/subscribe`)
      *     before any traffic flows. Each is matched by a name fixed ahead of time ([[dapr4s.publish.Topic]], [[dapr4s.jobs.JobName]],
      *     [[dapr4s.actor.ReminderName]]…).
      *   - '''This subscription''' is opened by the app — it issues an outbound streaming call to the sidecar — and is
      *     '''dynamic and scoped''': the keys are chosen at runtime and the stream is torn down on demand via the returned
      *     `AutoCloseable`. There is no static route to register, and capture checking ties the handle's lifetime to this
      *     capability's scope.
      *
      * So this is genuinely a `my-app-calls-DAPR` capability; the callback is just the continuation of the outbound stream
      * the app opened. Modelling it as a [[DaprApp]] route would fabricate a static route for something inherently dynamic
      * and forfeit the capture-checked lifecycle.
      *
      * Consequently this is the only capability that takes a callback, and the only `DAPR-pushes-to-me` feature without a
      * [[DaprApp]] dual. Features whose delivery target '''is''' known at startup use the split register-here /
      * handle-in-[[DaprApp]] idiom instead (publish ↔ [[dapr4s.publish.Subscription]], schedule ↔ [[dapr4s.jobs.JobRoute]],
      * `registerReminder` ↔ [[dapr4s.actor.ActorReminderRoute]], `registerTimer` ↔ [[dapr4s.actor.ActorTimerRoute]]).
      *
      * @param metadata
      *   optional metadata passed to the configuration backend
      */
    def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
        onChange: ConfigurationUpdate => Unit,
    ): AutoCloseable^{this}

  /** [[ConfigurationCapability.Instance]]'s read-only view, and the forwarders for
    * [[ConfigurationCapability.Instance]].
    */
  @scala.caps.assumeSafe
  object Instance:

    /** Capability bound to one named configuration store, read-only. */
    @scala.caps.assumeSafe
    trait ReadOnly extends scala.caps.ExclusiveCapability:
      /** The configuration store this capability is bound to. */
      val storeName: ConfigurationStoreName

      /** Retrieve one or more configuration items by key.
        *
        * @param metadata
        *   optional metadata passed to the configuration backend
        */
      def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[ConfigurationKey, ConfigurationItem]

    /** Forwarders for [[ConfigurationCapability.Instance.ReadOnly]]. */
    @scala.caps.assumeSafe
    object ReadOnly:
      def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
          cap: ReadOnly,
      ): Map[ConfigurationKey, ConfigurationItem] =
        cap.get(keys, metadata)

    def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
        cap: Instance,
    ): Map[ConfigurationKey, ConfigurationItem] =
      cap.get(keys, metadata)
    def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
        onChange: ConfigurationUpdate => Unit,
    )(using cap: Instance): AutoCloseable^{cap} =
      cap.subscribe(keys, metadata)(onChange)

  /** Narrow the universal capability in scope to one store and return it. */
  def instance(storeName: ConfigurationStoreName)(using cap: ConfigurationCapability): Instance^{cap} =
    cap.instance(storeName)
  def get(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: ConfigurationCapability): Map[ConfigurationKey, ConfigurationItem] =
    cap.get(storeName, keys, metadata)
  def subscribe(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(onChange: ConfigurationUpdate => Unit)(using cap: ConfigurationCapability): AutoCloseable^{cap} =
    cap.subscribe(storeName, keys, metadata)(onChange)
