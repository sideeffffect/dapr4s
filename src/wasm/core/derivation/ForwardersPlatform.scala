package dapr4s.derivation

/** Wasm-component half of [[Forwarders]] — empty for now, like the Scala.js twin: the jobs forwarders forward to the
  * JVM-only `dapr4s.jobs.JobsCapability`, which the Phase-0 `wasm` wall does not provide yet (see
  * `src/wasm/core/DaprCapabilityPlatform.scala`).
  *
  * WHY @assumeSafe: symmetry with the JVM/JS twins; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait ForwardersPlatform
