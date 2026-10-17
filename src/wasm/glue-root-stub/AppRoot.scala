// HAND-WRITTEN Scala 2.13 (why 2.13: see src/wasm/glue/WitGlue.scala).
package dapr4s.internal.witglue

/** LINK-TIME CONTRACT STUB, never linked: `witExports` depends on this project as `Provided`.
  *
  * The 2.13 `@WitExport` trampolines (src/wasm/glue-exports) must call into Scala 3 code (the wall), but a 2.13 project
  * cannot depend on a Scala 3 project. So they reference this fixed FQN, and the Scala 3 side supplies the real
  * `object dapr4s.internal.witglue.AppRoot` with the same members (src/wasm/internal/AppRoot.scala). A missing one is a
  * LINK error ("Referring to non-existent class dapr4s.internal.witglue.AppRoot$"), not a runtime surprise.
  */
object AppRoot {
  private def stub: Nothing = throw new UnsupportedOperationException("link-time stub")
  def invocationCallback: InvocationCallbackExports = stub
  def pubsubCallback: PubsubCallbackExports = stub
  def bindingsCallback: BindingsCallbackExports = stub
  def jobsCallback: JobsCallbackExports = stub
  def actorsCallback: ActorsCallbackExports = stub
  def configurationCallback: ConfigurationCallbackExports = stub
  def healthCallback: HealthCallbackExports = stub
}
