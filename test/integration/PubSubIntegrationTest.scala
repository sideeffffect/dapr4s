package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Integration tests for [[PubSubCapability]] using a real DAPR sidecar in
  * Docker via Testcontainers.
  */
@scala.caps.assumeSafe
class PubSubIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val component = Component(
      "pubsub",
      "pubsub.in-memory",
      "v1",
      Collections.emptyMap[String, String]()
    )
    DaprTestContainer(
      DaprContainer("daprio/daprd:latest")
        .withAppName("pubsub-test-app")
        .withAppPort(0)
        .withComponent(component)
    )

  // -------------------------------------------------------------------------

  test("integration: publish string payload does not throw"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
        ps.publish(Topic("test-topic"), "hello-pubsub")
    }

  test("integration: publishWithMetadata does not throw"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
        ps.publishWithMetadata(
          Topic("test-topic"),
          "with-metadata",
          Map("traceparent" -> "00-abc-def-01")
        )
    }

  test("integration: publish Int payload does not throw"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
        ps.publish(Topic("numbers"), 42)
    }
