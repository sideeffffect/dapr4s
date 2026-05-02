package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.{DaprContainer, Component}
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[PubSubCapability]] using a real DAPR sidecar. */
class PubSubIntegrationTest extends FunSuite:

  private var dapr: DaprContainer = null

  override def beforeAll(): Unit =
    val pubsubComponent = Component(
      "pubsub",
      "pubsub.in-memory",
      "v1",
      Collections.emptyMap[String, String]()
    )
    dapr = DaprContainer("daprio/daprd:latest")
      .withAppName("pubsub-test-app")
      .withAppPort(0)
      .withComponent(pubsubComponent)
    dapr.start()

  override def afterAll(): Unit =
    if dapr != null then dapr.stop()

  private def httpEndpoint = s"http://${dapr.getHost}:${dapr.getHttpPort}"
  private def grpcEndpoint = s"http://${dapr.getHost}:${dapr.getGrpcPort}"

  // -------------------------------------------------------------------------

  test("integration: publish string payload does not throw"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
      ps.publish(Topic("test-topic"), "hello-pubsub")

  test("integration: publishWithMetadata does not throw"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
      ps.publishWithMetadata(
        Topic("test-topic"),
        "with-metadata",
        Map("traceparent" -> "00-abc-def-01")
      )

  test("integration: publish Int payload does not throw"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val ps = summon[DaprScope].pubsub(PubSubName("pubsub"))
      ps.publish(Topic("numbers"), 42)
