package dapr4s.configuration

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for configuration stores: an "any store" handle obtained argument-less via
  * [[DaprCapability.configuration]], whose [[apply]] narrows to a [[ConfigurationCapability]] bound to one store.
  */
@scala.caps.assumeSafe
trait AccessConfigurationCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[ConfigurationCapability]] for the named configuration store. */
  def apply(storeName: ConfigurationStoreName): ConfigurationCapability^{this}

/** Capability for reading configuration items from a named DAPR config store. */
@scala.caps.assumeSafe
trait ConfigurationCapability extends scala.caps.ExclusiveCapability:
  val storeName: ConfigurationStoreName

  /** Retrieve one or more configuration items by key.
    *
    * @param metadata
    *   optional metadata passed to the configuration backend
    */
  def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[ConfigurationKey, ConfigurationItem]

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
    * inbound handler (DAPR pushing data into the app), which would suggest it belongs alongside [[Subscription]],
    * [[JobRoute]], and the actor reminder/timer routes in [[DaprApp]]. It does not, because those routes and this
    * subscription differ on the axis that actually separates the two layers — connection initiation and lifecycle, not
    * data direction:
    *
    *   - '''[[DaprApp]] routes''' are opened by the sidecar (the sidecar is the client, the app is the HTTP server) and
    *     must be declared '''statically at startup''' so the sidecar can enumerate them (e.g. via `/dapr/subscribe`)
    *     before any traffic flows. Each is matched by a name fixed ahead of time ([[Topic]], [[JobName]],
    *     [[ReminderName]]…).
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
    * handle-in-[[DaprApp]] idiom instead (publish ↔ [[Subscription]], schedule ↔ [[JobRoute]],
    * `registerReminder` ↔ [[ActorReminderRoute]], `registerTimer` ↔ [[ActorTimerRoute]]).
    *
    * @param metadata
    *   optional metadata passed to the configuration backend
    */
  def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigurationUpdate => Unit,
  ): AutoCloseable^{this}

/** Companion-object API for [[ConfigurationCapability]].
  *
  * Forwards to the `ConfigurationCapability` in the enclosing `using` context:
  * {{{
  *   def featureFlag()(using ConfigurationCapability): Boolean =
  *     ConfigurationCapability.get(Seq(ConfigurationKey("feature-x")))
  *       .get(ConfigurationKey("feature-x")).exists(_.value.value == "true")
  * }}}
  */
@scala.caps.assumeSafe
object ConfigurationCapability:
  def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: ConfigurationCapability,
  ): Map[ConfigurationKey, ConfigurationItem] =
    cap.get(keys, metadata)
  def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigurationUpdate => Unit,
  )(using cap: ConfigurationCapability): AutoCloseable^{cap} =
    cap.subscribe(keys, metadata)(onChange)

