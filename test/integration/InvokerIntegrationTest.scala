package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite

/** Integration tests for [[ServiceInvocationCapability]].
  *
  * Service invocation requires a running target application. These tests
  * verify that the wrapper correctly propagates the [[DaprException]] from
  * the sidecar when no target is available (expected in CI without a real
  * peer app).
  */
class InvokerIntegrationTest extends FunSuite:

  private var dapr: DaprContainer = null

  override def beforeAll(): Unit =
    dapr = DaprContainer("daprio/daprd:latest")
      .withAppName("invoker-test-app")
      .withAppPort(0)
    dapr.start()

  override def afterAll(): Unit =
    if dapr != null then dapr.stop()

  private def httpEndpoint = s"http://${dapr.getHost}:${dapr.getHttpPort}"
  private def grpcEndpoint = s"http://${dapr.getHost}:${dapr.getGrpcPort}"

  // -------------------------------------------------------------------------

  test("integration: invoke non-existent app throws DaprException"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val invoker = summon[DaprScope].invoker
      intercept[DaprException]:
        invoker.invoke[String, String](AppId("no-such-app"), "method", "data")

  test("integration: invokeGet non-existent app throws DaprException"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val invoker = summon[DaprScope].invoker
      intercept[DaprException]:
        invoker.invokeGet[String](AppId("no-such-app"), "method")
