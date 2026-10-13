package dapr4s.internal

import scala.scalajs.wit

import dapr4swit.dapr_wasm_components.interfaces.types
import dapr4swit.exports.dapr_wasm_components.interfaces.{
  actors_callback,
  bindings_callback,
  configuration_callback,
  health_callback,
  jobs_callback,
  pubsub_callback,
}

/** Inert implementations of the callback exports the Phase-0 `wasm` wall does not dispatch yet. World `app` makes all
  * seven `*-callback` exports mandatory, so a component must implement them even when the app has no subscriptions,
  * input bindings, jobs or actors: these answer "nothing registered".
  *
  * Shared by the dapr4s wall (src/wasm/internal/AppRoot.scala) and the raw test component (test/wasm/counter-raw).
  * Phase 4 replaces them, one by one, with dispatchers over the app's `DaprApp` (like `InvokeDispatcher`).
  */
object InertCallbacks:
  object PubSub extends pubsub_callback.PubsubCallbackExports:
    override def listTopicSubscriptions(): Array[pubsub_callback.TopicSubscription] = Array()
    override def onTopicEvent(event: pubsub_callback.TopicEvent): pubsub_callback.TopicEventResponse =
      pubsub_callback.TopicEventResponse.Drop
    override def onBulkTopicEvent(
        event: pubsub_callback.BulkTopicEvent,
    ): Array[pubsub_callback.BulkTopicEventEntryResponse] =
      event.entries.map(e =>
        pubsub_callback.BulkTopicEventEntryResponse(e.entryId, pubsub_callback.TopicEventResponse.Drop),
      )

  object Bindings extends bindings_callback.BindingsCallbackExports:
    override def listInputBindings(): Array[String] = Array()
    override def onBindingEvent(
        event: bindings_callback.BindingEvent,
    ): wit.Result[bindings_callback.BindingEventResponse, bindings_callback.AppError] =
      new wit.Err(types.AppError(s"no input binding ${event.name}"))

  object Jobs extends jobs_callback.JobsCallbackExports:
    override def onJobEvent(event: jobs_callback.JobEvent): wit.Result[Unit, jobs_callback.AppError] =
      new wit.Err(types.AppError(s"no job handler ${event.name}"))

  object Actors extends actors_callback.ActorsCallbackExports:
    override def config(): actors_callback.ActorConfig =
      actors_callback.ActorConfig(Array(), wit.None, wit.None, wit.None, wit.None, wit.None, wit.None, wit.None)
    override def onInvoke(
        actorType: String,
        actorId: String,
        method: String,
        data: Array[Byte],
    ): wit.Result[Array[Byte], actors_callback.AppError] =
      new wit.Err(types.AppError(s"no actor type $actorType"))
    override def onTimer(event: actors_callback.ActorTimerEvent): wit.Result[Unit, actors_callback.AppError] =
      new wit.Err(types.AppError("no actors"))
    override def onReminder(event: actors_callback.ActorReminderEvent): wit.Result[Unit, actors_callback.AppError] =
      new wit.Err(types.AppError("no actors"))
    override def deactivate(actorType: String, actorId: String): wit.Result[Unit, actors_callback.AppError] =
      new wit.Ok(())

  object Configuration extends configuration_callback.ConfigurationCallbackExports:
    override def onConfigurationEvent(update: configuration_callback.ConfigurationUpdate): Unit = ()

  object Health extends health_callback.HealthCallbackExports:
    override def healthCheck(): wit.Result[Unit, health_callback.AppError] = new wit.Ok(())
