package dapr4s.internal.witglue

import scala.scalajs.wit
import scala.scalajs.wit.annotation.{WitExport, WitName, WitScope}

import dapr4swit.dapr_wasm_components.interfaces.{invocation, state, state_read, types}
import dapr4swit.exports.dapr_wasm_components.{interfaces => ex}

/** Outbound forwarders. A WIT import call is lowered at the CALL SITE by the WIT-aware compiler backend
  * (GenWitInterop.genWitNativeMemberCall); the binding method itself has no IR body. So a non-WIT-aware compiler
  * (dapr4s's nightly) cannot call `state_read.get` directly: it would emit an ordinary call to a method that does not
  * exist at link time. These forwarders are compiled by the WIT-aware compiler and expose ordinary methods with the
  * generated types unchanged.
  *
  * Hand-written for the Phase-0 prototype (the 6 state functions). The full `wasm` platform needs one per WIT import
  * (~100), so they should be generated (an sbt source generator over the wit-bindgen output).
  *
  * Authority (W1) is unaffected by forwarding every operation: the linker drops forwarders nobody reaches, and
  * `wasm-tools component new` drops the then-unused imports, so a component imports only what it calls.
  */
object StateForward {
  type Metadata = Array[wit.Tuple2[String, String]]

  def get(
      storeName: String,
      key: String,
      consistency: wit.Option[state_read.Consistency],
      metadata: Metadata,
  ): wit.Result[state_read.GetStateResponse, state_read.GetError] =
    state_read.get(storeName, key, consistency, metadata)

  def getBulk(
      storeName: String,
      keys: Array[String],
      metadata: Metadata,
  ): wit.Result[Array[state_read.BulkStateItem], state_read.StateError] =
    state_read.getBulk(storeName, keys, wit.None, metadata)

  def query(
      storeName: String,
      query: String,
      metadata: Metadata,
  ): wit.Result[state_read.QueryResponse, state_read.QueryError] =
    state_read.query(storeName, query, metadata)

  def save(
      storeName: String,
      items: Array[state_read.StateItem],
      metadata: Metadata,
  ): wit.Result[Unit, state.WriteError] =
    state.save(storeName, items, metadata)

  def delete(
      storeName: String,
      key: String,
      etag: wit.Option[String],
      options: wit.Option[state_read.StateOptions],
      metadata: Metadata,
  ): wit.Result[Unit, state.WriteError] =
    state.delete(storeName, key, etag, options, metadata)

  def executeTransaction(
      storeName: String,
      operations: Array[state.TransactionRequest],
      metadata: Metadata,
  ): wit.Result[Unit, state.TransactionError] =
    state.executeTransaction(storeName, operations, metadata)
}

/** The 7 mandatory callback exports of world `app`. Only invocation is delegated to the app (via the link-time
  * `AppRoot` contract, see src/wasm/wit/root-stub); the rest are inert defaults in this Phase-0 prototype (no
  * subscriptions, no input bindings, no actors, no jobs). `pubsub-callback.on-topic-event` (22 flat params) and
  * `actors-callback.on-timer` (17) need the patched linker (scripts/wasm/patches/).
  */
object InvocationCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "invocation-callback", "0.7.0"), "on-invoke")
  def onInvoke(@WitName("request") request: ex.invocation_callback.InvokeRequest): invocation.HttpResponse =
    AppRoot.invocation.onInvoke(request)

}

object HealthCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "health-callback", "0.7.0"), "health-check")
  def healthCheck(): wit.Result[Unit, types.AppError] = new wit.Ok(())

}

object PubsubCallbackExport {
  import ex.{pubsub_callback => pc}
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.0"), "list-topic-subscriptions")
  def listTopicSubscriptions(): Array[pc.TopicSubscription] = Array()
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.0"), "on-topic-event")
  def onTopicEvent(@WitName("event") event: pc.TopicEvent): pc.TopicEventResponse = pc.TopicEventResponse.Drop
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.0"), "on-bulk-topic-event")
  def onBulkTopicEvent(@WitName("event") event: pc.BulkTopicEvent): Array[pc.BulkTopicEventEntryResponse] =
    event.entries.map(e => pc.BulkTopicEventEntryResponse(e.entryId, pc.TopicEventResponse.Drop))

}

object BindingsCallbackExport {
  import ex.{bindings_callback => bc}
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "bindings-callback", "0.7.0"), "list-input-bindings")
  def listInputBindings(): Array[String] = Array()
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "bindings-callback", "0.7.0"), "on-binding-event")
  def onBindingEvent(@WitName("event") event: bc.BindingEvent): wit.Result[bc.BindingEventResponse, types.AppError] =
    new wit.Err(types.AppError(s"no input binding ${event.name}"))

}

object JobsCallbackExport {
  import ex.{jobs_callback => jc}
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "jobs-callback", "0.7.0"), "on-job-event")
  def onJobEvent(@WitName("event") event: jc.JobEvent): wit.Result[Unit, types.AppError] =
    new wit.Err(types.AppError(s"no job handler ${event.name}"))

}

object ActorsCallbackExport {
  import ex.{actors_callback => ac}
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.0"), "config")
  def config(): ac.ActorConfig =
    ac.ActorConfig(Array(), wit.None, wit.None, wit.None, wit.None, wit.None, wit.None, wit.None)
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.0"), "on-invoke")
  def onInvoke(
      @WitName("actor-type") actorType: String,
      @WitName("actor-id") actorId: String,
      @WitName("method") method: String,
      @WitName("data") data: Array[Byte],
  ): wit.Result[Array[Byte], types.AppError] =
    new wit.Err(types.AppError(s"no actor type $actorType"))
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.0"), "on-timer")
  def onTimer(@WitName("event") event: ac.ActorTimerEvent): wit.Result[Unit, types.AppError] =
    new wit.Err(types.AppError("no actors"))
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.0"), "on-reminder")
  def onReminder(@WitName("event") event: ac.ActorReminderEvent): wit.Result[Unit, types.AppError] =
    new wit.Err(types.AppError("no actors"))
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.0"), "deactivate")
  def deactivate(
      @WitName("actor-type") actorType: String,
      @WitName("actor-id") actorId: String,
  ): wit.Result[Unit, types.AppError] = new wit.Ok(())

}

object ConfigurationCallbackExport {
  import ex.{configuration_callback => cc}
  @WitExport(
    WitScope("dapr-wasm-components", "interfaces", "configuration-callback", "0.7.0"),
    "on-configuration-event",
  )
  def onConfigurationEvent(@WitName("update") update: cc.ConfigurationUpdate): Unit = ()

}
