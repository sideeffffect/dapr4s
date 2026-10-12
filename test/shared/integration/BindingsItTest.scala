package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[BindingsCapability]] integration suite — a SINGLE cross-platform file, run via the per-platform
  * [[SharedDaprItSuite]] bring-up. The canonical component set has no output binding, so this covers the error path
  * only: daprd reports a missing output binding as `ERR_INVOKE_OUTPUT_BINDING` ("couldn't find output binding"), which
  * both platforms must surface as `ComponentNotFoundException(Binding, name)`.
  */
@scala.caps.assumeSafe
class BindingsItTest extends FunSuite, SharedDaprItSuite:

  test("bindings: an unknown output binding throws ComponentNotFoundException(Binding)")(withDapr(unknownBinding))

  def unknownBinding(using DaprCapability): Unit =
    DaprCapability.bindings(BindingName("no-such-binding")):
      ItErrors.assertComponentNotFound(DaprComponentKind.Binding, "no-such-binding")(
        BindingsCapability.Instance.invokeOneWay(BindingOperation("create"), "x"),
      )
