package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given

/** Domain types for the Counter actor integration tests. */

final case class IncrRequest(amount: Int)
@scala.caps.assumeSafe
object IncrRequest:
  given JsonCodec[IncrRequest] = upickleCodec(using upickle.default.macroRW)

final case class CounterState(count: Int)
@scala.caps.assumeSafe
object CounterState:
  given JsonCodec[CounterState] = upickleCodec(using upickle.default.macroRW)
