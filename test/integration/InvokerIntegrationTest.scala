package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

/** Integration tests for [[ServiceInvocationCapability]].
  *
  * Service invocation requires a running target application. These tests
  * verify that the wrapper correctly propagates the [[DaprException]] from
  * the sidecar when no target is available (expected in CI without a real
  * peer app).
  */
@scala.caps.assumeSafe
class InvokerIntegrationTest extends FunSuite:

  private var dapr: DaprContainer | Null = null

  override def beforeAll(): Unit =
    val d = DaprContainer("daprio/daprd:latest")
      .withAppName("invoker-test-app")
      .withAppPort(0)
    d.start()
    dapr = d

  override def afterAll(): Unit =
    val d = dapr
    if d != null then d.stop()

  private def httpEndpoint =
    val d = dapr.nn; s"http://${d.getHost}:${d.getHttpPort}"
  private def grpcEndpoint =
    val d = dapr.nn; s"http://${d.getHost}:${d.getGrpcPort}"

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
