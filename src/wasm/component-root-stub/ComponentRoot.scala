package dapr4s.wasm

/** LINK-TIME CONTRACT STUB, never linked (`wallWasm` depends on it as `Provided`).
  *
  * WHY: a WASI reactor component has no `main`. scala-wasm runs module initializers only behind a configured component
  * export (Emitter: "Wasm Component module initializers require a configured Wasm Component module initializer
  * export"), and Scala.js reflection (`scala.scalajs.reflect.Reflect`) is JS-backed. So the library's `@WitExport` glue
  * cannot discover the app at run time; the wall references this FQN statically instead, and the application supplies
  * the real `object dapr4s.wasm.ComponentRoot`. A missing one is a LINK error ("Referring to non-existent class
  * dapr4s.wasm.ComponentRoot$"), not a runtime surprise.
  */
object ComponentRoot:
  def component: DaprComponent = throw new UnsupportedOperationException("link-time stub")
