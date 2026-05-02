package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.{DaprContainer, Component}
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Integration tests for [[PubSubCapability]] using a real DAPR sidecar. */
@scala.caps.assumeSafe
class PubSubIntegrationTest extends FunSuite:

  private var dapr: DaprContainer | Null = null

  override def beforeAll(): Unit =
    val pubsubComponent = Component(
      "pubsub",
      "pubsub.in-memory",
      "v1",
      Collections.emptyMap[String, String]()
    )
    val d = DaprContainer("daprio/daprd:latest")
      .withAppName("pubsub-test-app")
      .withAppPort(0)
      .withComponent(pubsubComponent)
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
