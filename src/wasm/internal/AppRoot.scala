package dapr4s.internal.witglue

/** The Scala 3 side of the 2.13 glue's link-time contract (stub: src/wasm/wit/root-stub/AppRoot.scala). The 15
  * `@WitExport` functions live in the 2.13 `witExports` project and call this object, which the dapr4s wall (a library,
  * not the application) provides. The application only supplies `dapr4s.wasm.ComponentRoot`.
  */
object AppRoot:
  def invocation: InvocationHandler = dapr4s.internal.WasmRuntime.invocation
