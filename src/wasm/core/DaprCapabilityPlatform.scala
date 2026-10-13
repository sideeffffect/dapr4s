package dapr4s

/** Wasm-component half of the [[DaprCapability]] surface — empty for now (Phase 0 prototype).
  *
  * The WIT world `dapr-wasm-components:interfaces/app@0.7.0` does have jobs and conversation, so the finished `wasm`
  * platform will contribute them here (docs/WASM-COMPONENTS-PROPOSAL.md §2c). The Phase-0 prototype wires only state
  * (`dapr4s.internal.WasmStateCapability`), and an empty trait keeps `DaprCapability.jobs` a compile-time error on this
  * platform until the wall implements it — never a runtime `UnsupportedOperationException` (AGENTS.md "Platform-
  * diverging public surface").
  *
  * WHY @assumeSafe: symmetry with the JVM/JS twins; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait DaprCapabilityPlatform

/** Wasm-component half of the [[DaprCapability$ DaprCapability companion]] transformer API — empty for the same reason
  * as [[DaprCapabilityPlatform]].
  *
  * WHY @assumeSafe: symmetry with the JVM/JS twins; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait DaprCapabilityCompanionPlatform
