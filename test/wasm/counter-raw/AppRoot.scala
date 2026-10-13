package dapr4s.internal.witglue

/** This raw app bypasses the dapr4s wall, so it supplies the 2.13 glue's link-time contract itself (stub:
  * src/wasm/wit/root-stub/AppRoot.scala; same FQN and members). The `@WitExport` functions call it.
  */
object AppRoot:
  def invocation: InvocationHandler = dapr4s.test.wasm.raw.CounterHandler
