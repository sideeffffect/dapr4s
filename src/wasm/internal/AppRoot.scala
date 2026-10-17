package dapr4s.internal.witglue

import dapr4s.internal.{InertCallbacks, WasmRuntime}

/** The Scala 3 side of the 2.13 glue's link-time contract (the stub src/wasm/glue-root-stub/AppRoot.scala has the same
  * FQN and members). The `@WitExport` trampolines (src/wasm/glue-exports) call this object, which the dapr4s wall (a
  * library, not the application) provides. The application only supplies `dapr4s.wasm.ComponentRoot`.
  */
object AppRoot:
  def invocationCallback: InvocationCallbackExports = WasmRuntime.invocation
  def pubsubCallback: PubsubCallbackExports = InertCallbacks.PubSub
  def bindingsCallback: BindingsCallbackExports = InertCallbacks.Bindings
  def jobsCallback: JobsCallbackExports = InertCallbacks.Jobs
  def actorsCallback: ActorsCallbackExports = InertCallbacks.Actors
  def configurationCallback: ConfigurationCallbackExports = InertCallbacks.Configuration
  def healthCallback: HealthCallbackExports = InertCallbacks.Health
