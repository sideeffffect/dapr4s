//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import org.testcontainers.containers.Network

/** Integration tests for [[PublishCapability]] using a real DAPR sidecar in Docker via Testcontainers, publishing to
  * the canonical `pubsub.redis` component (the shared scripts/it/components set, matching the JS harness — see
  * [[RedisFixture]]).
  */
@scala.caps.assumeSafe
class PubSubIntegrationTest extends FunSuite, RedisFixture:

  override type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val network = Network.newNetwork()
    val res = startRedis(network)
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("pubsub-test-app")
        .withAppPort(0)
        .withComponent(res.component("pubsub")),
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
