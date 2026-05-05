package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite

/** Integration tests for [[ServiceInvocationCapability]].
  *
  * Service invocation requires a running target application. These tests verify that the wrapper correctly propagates
  * the [[DaprException]] from the sidecar when no target is available (expected in CI without a real peer app).
  */
@scala.caps.assumeSafe
class InvokerIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("invoker-test-app")
        .withAppPort(0),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("integration: invoke non-existent app throws DaprException"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoker = summon[DaprCapability].invoker
        intercept[io.dapr.exceptions.DaprException]:
          invoker.invoke(AppId("no-such-app"), MethodName("method"), "data")[String]
    }

  test("integration: invokeGet non-existent app throws DaprException"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoker = summon[DaprCapability].invoker
        intercept[io.dapr.exceptions.DaprException]:
          invoker.invokeGet[String](AppId("no-such-app"), MethodName("method"))
    }
