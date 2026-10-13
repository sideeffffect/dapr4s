// HAND-WRITTEN Scala 2.13 (why 2.13: see src/wasm/glue/WitGlue.scala).
//
// The `@WitExport` trampolines of the 7 mandatory `*-callback` interfaces of world
// `dapr-wasm-components:interfaces/app@0.7.1`. Each one delegates to the Scala 3 implementation behind the fixed-name
// object `AppRoot` (stub: src/wasm/glue-root-stub; real one: the wall's src/wasm/internal/AppRoot.scala).
//
// Only COMPONENTS depend on this project (`witExports`): every `@WitExport` is a linker entry point, and a plain
// Scala.js link (the wall's unit tests on Node) rejects them ("Invalid top level export").
package dapr4s.internal.witglue

import scala.scalajs.wit
import scala.scalajs.wit.annotation.{WitExport, WitName, WitScope}

import dapr4swit.exports.dapr_wasm_components.interfaces.{
  actors_callback,
  bindings_callback,
  configuration_callback,
  health_callback,
  invocation_callback,
  jobs_callback,
  pubsub_callback,
}

object InvocationCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "invocation-callback", "0.7.1"), "on-invoke")
  def onInvoke(@WitName("request") request: invocation_callback.InvokeRequest): invocation_callback.HttpResponse =
    AppRoot.invocationCallback.onInvoke(request)
}

object PubsubCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.1"), "list-topic-subscriptions")
  def listTopicSubscriptions(): Array[pubsub_callback.TopicSubscription] =
    AppRoot.pubsubCallback.listTopicSubscriptions()

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.1"), "on-topic-event")
  def onTopicEvent(@WitName("event") event: pubsub_callback.TopicEvent): pubsub_callback.TopicEventResponse =
    AppRoot.pubsubCallback.onTopicEvent(event)

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "pubsub-callback", "0.7.1"), "on-bulk-topic-event")
  def onBulkTopicEvent(
      @WitName("event") event: pubsub_callback.BulkTopicEvent,
  ): Array[pubsub_callback.BulkTopicEventEntryResponse] =
    AppRoot.pubsubCallback.onBulkTopicEvent(event)
}

object BindingsCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "bindings-callback", "0.7.1"), "list-input-bindings")
  def listInputBindings(): Array[String] =
    AppRoot.bindingsCallback.listInputBindings()

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "bindings-callback", "0.7.1"), "on-binding-event")
  def onBindingEvent(
      @WitName("event") event: bindings_callback.BindingEvent,
  ): wit.Result[bindings_callback.BindingEventResponse, bindings_callback.AppError] =
    AppRoot.bindingsCallback.onBindingEvent(event)
}

object JobsCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "jobs-callback", "0.7.1"), "on-job-event")
  def onJobEvent(@WitName("event") event: jobs_callback.JobEvent): wit.Result[Unit, jobs_callback.AppError] =
    AppRoot.jobsCallback.onJobEvent(event)
}

object ActorsCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.1"), "config")
  def config(): actors_callback.ActorConfig =
    AppRoot.actorsCallback.config()

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.1"), "on-invoke")
  def onInvoke(
      @WitName("actor-type") actorType: String,
      @WitName("actor-id") actorId: String,
      @WitName("method") method: String,
      @WitName("data") data: Array[wit.unsigned.UByte],
  ): wit.Result[Array[wit.unsigned.UByte], actors_callback.AppError] =
    AppRoot.actorsCallback.onInvoke(actorType, actorId, method, data)

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.1"), "on-timer")
  def onTimer(@WitName("event") event: actors_callback.ActorTimerEvent): wit.Result[Unit, actors_callback.AppError] =
    AppRoot.actorsCallback.onTimer(event)

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.1"), "on-reminder")
  def onReminder(
      @WitName("event") event: actors_callback.ActorReminderEvent,
  ): wit.Result[Unit, actors_callback.AppError] =
    AppRoot.actorsCallback.onReminder(event)

  @WitExport(WitScope("dapr-wasm-components", "interfaces", "actors-callback", "0.7.1"), "deactivate")
  def deactivate(
      @WitName("actor-type") actorType: String,
      @WitName("actor-id") actorId: String,
  ): wit.Result[Unit, actors_callback.AppError] =
    AppRoot.actorsCallback.deactivate(actorType, actorId)
}

object ConfigurationCallbackExport {
  @WitExport(
    WitScope("dapr-wasm-components", "interfaces", "configuration-callback", "0.7.1"),
    "on-configuration-event",
  )
  def onConfigurationEvent(@WitName("update") update: configuration_callback.ConfigurationUpdate): Unit =
    AppRoot.configurationCallback.onConfigurationEvent(update)
}

object HealthCallbackExport {
  @WitExport(WitScope("dapr-wasm-components", "interfaces", "health-callback", "0.7.1"), "health-check")
  def healthCheck(): wit.Result[Unit, health_callback.AppError] =
    AppRoot.healthCallback.healthCheck()
}
