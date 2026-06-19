package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given
import dapr4s.derivation.*
import language.experimental.safe

/** A derived service-invocation client used by `InvokeCapabilityServerTest` to prove
  * [[dapr4s.derivation.Invoke.derive]] works over a real Dapr sidecar.
  *
  * The method names map verbatim to the `echo` and `double` [[InvokeRoute]]s registered by that test's app server.
  * Declared in safe mode to confirm the derived facade composes with capture-checked, safe-mode user code.
  */
trait EchoService:
  def echo(req: String)(using AccessInvokeCapability, JsonCodec[String]): String

  def double(
      req: IncrRequest,
  )(using AccessInvokeCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState

def EchoService(appId: AppId): EchoService = Invoke.derive[EchoService](appId)
