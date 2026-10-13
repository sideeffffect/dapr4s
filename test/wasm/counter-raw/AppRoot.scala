package dapr4s.internal.witglue

import dapr4s.internal.InertCallbacks
import dapr4swit.exports.dapr_wasm_components.interfaces.{
  actors_callback,
  bindings_callback,
  configuration_callback,
  health_callback,
  invocation_callback,
  jobs_callback,
  pubsub_callback,
}

/** This raw component bypasses the dapr4s wall, so it supplies the generated glue's link-time contract itself (same FQN
  * and members as the generated stub, wasm/project/WitGlue.scala `rootStub`). The `@WitExport` trampolines call it.
  */
object AppRoot:
  def invocationCallback: invocation_callback.InvocationCallbackExports = dapr4s.test.wasm.raw.CounterHandler
  def pubsubCallback: pubsub_callback.PubsubCallbackExports = InertCallbacks.PubSub
  def bindingsCallback: bindings_callback.BindingsCallbackExports = InertCallbacks.Bindings
  def jobsCallback: jobs_callback.JobsCallbackExports = InertCallbacks.Jobs
  def actorsCallback: actors_callback.ActorsCallbackExports = InertCallbacks.Actors
  def configurationCallback: configuration_callback.ConfigurationCallbackExports = InertCallbacks.Configuration
  def healthCallback: health_callback.HealthCallbackExports = InertCallbacks.Health
