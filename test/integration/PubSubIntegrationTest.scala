package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[PublishCapability]] using a real DAPR sidecar in Docker via Testcontainers.
  */
@scala.caps.assumeSafe
class PubSubIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val component = Component(
      "pubsub",
      "pubsub.in-memory",
      "v1",
      Collections.emptyMap[String, String](),
    )
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("pubsub-test-app")
        .withAppPort(0)
        .withComponent(component),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("integration: publish string payload does not throw"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprCapability].publish(PubSubName("pubsub"))
        ps.publish(Topic("test-topic"), "hello-pubsub")
    }

  test("integration: publishWithMetadata does not throw"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprCapability].publish(PubSubName("pubsub"))
        ps.publishWithMetadata(
          Topic("test-topic"),
          "with-metadata",
          Map(MetadataKey("traceparent") -> MetadataValue("00-abc-def-01")),
        )
    }

  test("integration: publish Int payload does not throw"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val ps = summon[DaprCapability].publish(PubSubName("pubsub"))
        ps.publish(Topic("numbers"), 42)
    }
