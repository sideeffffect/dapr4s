package dapr4s.wasm

import dapr4s.*
import dapr4s.state.*

/** The entry point of a dapr4s app built as a WASI component (the `wasm` platform's counterpart of `Dapr(...).serve`).
  *
  * The app returns its `DaprApp`, built from the capabilities the `wasm` wall provides (only state in the Phase-0
  * prototype). It then defines exactly one object with this fixed name, and nothing else:
  * {{{
  * package dapr4s.wasm
  * object ComponentRoot:
  *   def component: DaprComponent = my.app.MyComponent
  * }}}
  * See src/wasm/component-root-stub/ComponentRoot.scala for why the name is fixed (a reactor component has no `main`).
  */
trait DaprComponent:
  def app(using state: StateCapability): DaprApp
