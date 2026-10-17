package dapr4s.wasm

/** The application's side of the link-time contract (stub: src/wasm/component-root-stub/ComponentRoot.scala): the one
  * line a dapr4s wasm app has to write besides its `DaprComponent`.
  */
object ComponentRoot:
  def component: DaprComponent = dapr4s.test.wasm.CounterComponent
