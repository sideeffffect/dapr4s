package dapr4s.internal.witglue

import dapr4s.internal.InertCallbacks

/** This raw component bypasses the dapr4s wall, so it supplies the 2.13 glue's link-time contract itself (same FQN and
  * members as the stub src/wasm/glue-root-stub/AppRoot.scala). The `@WitExport` trampolines call it.
  */
object AppRoot:
  def invocationCallback: InvocationCallbackExports = dapr4s.test.wasm.raw.CounterHandler
  def pubsubCallback: PubsubCallbackExports = InertCallbacks.PubSub
  def bindingsCallback: BindingsCallbackExports = InertCallbacks.Bindings
  def jobsCallback: JobsCallbackExports = InertCallbacks.Jobs
  def actorsCallback: ActorsCallbackExports = InertCallbacks.Actors
  def configurationCallback: ConfigurationCallbackExports = InertCallbacks.Configuration
  def healthCallback: HealthCallbackExports = InertCallbacks.Health
