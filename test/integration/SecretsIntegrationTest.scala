package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite

/** Integration tests for [[SecretsCapability]].
  *
  * These tests verify the error path — calling against a component that does
  * not exist surfaces [[DaprException]].
  */
class SecretsIntegrationTest extends FunSuite:

  private var dapr: DaprContainer = null

  override def beforeAll(): Unit =
    dapr = DaprContainer("daprio/daprd:latest")
      .withAppName("secrets-test-app")
      .withAppPort(0)
    dapr.start()

  override def afterAll(): Unit =
    if dapr != null then dapr.stop()

  private def httpEndpoint = s"http://${dapr.getHost}:${dapr.getHttpPort}"
  private def grpcEndpoint = s"http://${dapr.getHost}:${dapr.getGrpcPort}"

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
