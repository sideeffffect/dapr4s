//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite

/** Integration tests for [[InvokeCapability]].
  *
  * Service invocation requires a running target application. These tests verify that the wrapper correctly propagates
  * the [[DaprException]] from the sidecar when no target is available (expected in CI without a real peer app).
  */
@scala.caps.assumeSafe
class InvokeIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("invoke-test-app")
        .withAppPort(0),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("integration: invoke non-existent app throws DaprException"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoke = summon[DaprCapability].invoke
        intercept[io.dapr.exceptions.DaprException]:
          invoke.invoke(AppId("no-such-app"), InvokeMethodName("method"), "data")[String]
    }

  test("integration: invokeGet non-existent app throws DaprException"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoke = summon[DaprCapability].invoke
        intercept[io.dapr.exceptions.DaprException]:
          invoke.invoke[String](AppId("no-such-app"), InvokeMethodName("method"))
    }
