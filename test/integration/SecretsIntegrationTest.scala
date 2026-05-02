package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

/** Integration tests for [[SecretsCapability]].
  *
  * These tests verify the error path — calling against a component that does
  * not exist surfaces [[DaprException]].
  */
@scala.caps.assumeSafe
class SecretsIntegrationTest extends FunSuite:

  private var dapr: DaprContainer | Null = null

  override def beforeAll(): Unit =
    val d = DaprContainer("daprio/daprd:latest")
      .withAppName("secrets-test-app")
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

  test("integration: get from non-configured secrets store throws DaprException"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val secrets = summon[DaprScope].secrets(SecretStoreName("nonexistent-store"))
      intercept[DaprException]:
        secrets.get("any-key")

  test("integration: getBulk from non-configured secrets store throws DaprException"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val secrets = summon[DaprScope].secrets(SecretStoreName("nonexistent-store"))
      intercept[DaprException]:
        secrets.getBulk()
