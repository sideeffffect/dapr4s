package dapr.safe.test.integration.apps

import upickle.default.{ReadWriter, macroRW}

/** Domain types for the Counter actor integration tests.
  *
  * Defined separately from [[CounterActorApp]] so their [[upickle]] [[ReadWriter]] instances can be marked
  * `@scala.caps.assumeSafe`, making them usable from safe-mode files that import `language.experimental.safe`.
  */

final case class IncrRequest(amount: Int)
@scala.caps.assumeSafe
object IncrRequest:
  given ReadWriter[IncrRequest] = macroRW

final case class CounterState(count: Int)
@scala.caps.assumeSafe
object CounterState:
  given ReadWriter[CounterState] = macroRW
