package dapr4s.internal.witglue

import dapr4s.internal.{InertCallbacks, WasmRuntime}
import dapr4swit.exports.dapr_wasm_components.interfaces.{
  actors_callback,
  bindings_callback,
  configuration_callback,
  health_callback,
  invocation_callback,
  jobs_callback,
  pubsub_callback,
}

/** The Scala 3 side of the generated glue's link-time contract (the generated stub has the same FQN and members, see
  * wasm/project/WitGlue.scala `rootStub`). The generated `@WitExport` trampolines call this object, which the dapr4s
  * wall (a library, not the application) provides. The application only supplies `dapr4s.wasm.ComponentRoot`.
  */
object AppRoot:
  def invocationCallback: invocation_callback.InvocationCallbackExports = WasmRuntime.invocation
  def pubsubCallback: pubsub_callback.PubsubCallbackExports = InertCallbacks.PubSub
  def bindingsCallback: bindings_callback.BindingsCallbackExports = InertCallbacks.Bindings
  def jobsCallback: jobs_callback.JobsCallbackExports = InertCallbacks.Jobs
  def actorsCallback: actors_callback.ActorsCallbackExports = InertCallbacks.Actors
  def configurationCallback: configuration_callback.ConfigurationCallbackExports = InertCallbacks.Configuration
  def healthCallback: health_callback.HealthCallbackExports = InertCallbacks.Health
