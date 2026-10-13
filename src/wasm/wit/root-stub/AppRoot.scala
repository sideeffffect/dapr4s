package dapr4s.internal.witglue

/** LINK-TIME CONTRACT STUB, never linked: `witExports` depends on this project as `Provided`, so it is not on any
  * component's classpath.
  *
  * WHY: the 2.13 `@WitExport` glue (src/wasm/wit/exports) must call into code compiled by the Scala 3 nightly (the
  * wall), but the 2.13 project cannot depend on a Scala 3 project. So the glue references this fixed FQN and the Scala
  * 3 side supplies the real `object dapr4s.internal.witglue.AppRoot` with the same members
  * (src/wasm/internal/AppRoot.scala; the raw test app test/wasm/counter-raw supplies its own). A missing one is a LINK
  * error ("Referring to non-existent class dapr4s.internal.witglue.AppRoot$"), not a runtime surprise.
  */
object AppRoot {
  def invocation: InvocationHandler = throw new UnsupportedOperationException("link-time stub")
}
