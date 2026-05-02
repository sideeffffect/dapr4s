package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

/** Integration tests for [[SecretsCapability]].
  *
  * These tests verify the error path — calling against a component that does not exist surfaces [[DaprException]].
  */
@scala.caps.assumeSafe
class SecretsIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
        .withAppName("secrets-test-app")
        .withAppPort(0),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("integration: get from non-configured secrets store throws DaprException"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val secrets = summon[DaprCapability].secrets(SecretStoreName("nonexistent-store"))
        intercept[DaprException]:
          secrets.get(SecretKey("any-key"))
    }

  test("integration: getBulk from non-configured secrets store throws DaprException"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val secrets = summon[DaprCapability].secrets(SecretStoreName("nonexistent-store"))
        intercept[DaprException]:
          secrets.getBulk()
    }
